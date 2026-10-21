# OPS-055 / ADR-09: PgBouncer in transaction mode, two instances in two AZs behind an internal NLB (HA).
# Business traffic (nabd_app, nabd_reports) uses pool.<zone>:6432. The relay's advisory lock + LISTEN and the migrate job
# use the DIRECT endpoint db.<zone>:5432 (session state does not survive transaction pooling). TenantContext uses
# set_config(..., true) (transaction-local), so tenant isolation is pool-safe.
locals {
  pooler_count  = 2
  pooler_server = local.pooler_count * (var.pgbouncer_app_pool_size + var.pgbouncer_reports_pool_size)
  pooler_ini = templatefile("${path.module}/data_tier_pgbouncer.ini.tftpl", {
    db_host      = aws_db_instance.this.address
    app_pool     = var.pgbouncer_app_pool_size
    reports_pool = var.pgbouncer_reports_pool_size
    max_client   = var.pgbouncer_max_client_conn
  })
}

resource "aws_security_group" "pooler" {
  name        = "${var.name}-pgbouncer"
  description = "PgBouncer: reachable only from inside the cell VPC"
  vpc_id      = aws_vpc.this.id
  tags        = merge(var.tags, { data_class = "network" })

  ingress {
    description = "PgBouncer from the VPC"
    from_port   = 6432
    to_port     = 6432
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  egress {
    description = "Postgres, and HTTPS to the VPC endpoints"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = [var.vpc_cidr]
  }
}

resource "aws_iam_role" "pooler" {
  name = "${var.name}-pgbouncer"
  tags = merge(var.tags, { data_class = "ops" })
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "ec2.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy" "pooler" {
  name = "pgbouncer"
  role = aws_iam_role.pooler.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = "secretsmanager:GetSecretValue"
        Resource = [aws_secretsmanager_secret.db_role["pgbouncer_auth"].arn, aws_secretsmanager_secret.db_role["pgbouncer_stats"].arn]
      },
      {
        Effect   = "Allow"
        Action   = "kms:Decrypt"
        Resource = aws_kms_key.root["ops"].arn
      },
      {
        Effect    = "Allow"
        Action    = "cloudwatch:PutMetricData"
        Resource  = "*"
        Condition = { StringEquals = { "cloudwatch:namespace" = "Nabd/PgBouncer" } }
      },
    ]
  })
}

resource "aws_iam_instance_profile" "pooler" {
  name = "${var.name}-pgbouncer"
  role = aws_iam_role.pooler.name
  tags = merge(var.tags, { data_class = "ops" })
}

resource "aws_launch_template" "pooler" {
  name_prefix            = "${var.name}-pgbouncer-"
  image_id               = var.pgbouncer_ami_id
  instance_type          = var.pgbouncer_instance_type
  vpc_security_group_ids = [aws_security_group.pooler.id]
  tags                   = merge(var.tags, { data_class = "network" })

  iam_instance_profile { name = aws_iam_instance_profile.pooler.name }
  metadata_options {
    http_endpoint = "enabled"
    http_tokens   = "required" # IMDSv2 only
  }
  block_device_mappings {
    device_name = "/dev/xvda"
    ebs {
      encrypted  = true
      kms_key_id = aws_kms_key.root["ops"].arn
    }
  }
  tag_specifications {
    resource_type = "instance"
    tags          = merge(var.tags, { data_class = "network", Name = "${var.name}-pgbouncer" })
  }
  user_data = base64encode(templatefile("${path.module}/data_tier_userdata.sh.tftpl", {
    ini              = local.pooler_ini
    auth_secret_arn  = aws_secretsmanager_secret.db_role["pgbouncer_auth"].arn
    stats_secret_arn = aws_secretsmanager_secret.db_role["pgbouncer_stats"].arn
    region           = var.region
    db_identifier    = aws_db_instance.this.identifier
  }))

  lifecycle {
    precondition {
      condition     = can(regex("^ami-[0-9a-f]{8,17}$", var.pgbouncer_ami_id))
      error_message = "pgbouncer_ami_id must be the baked PgBouncer AMI (infra/pgbouncer/AMI.md); there is no pooler without it."
    }
    precondition {
      condition     = local.pooler_server + var.db_direct_reserve <= var.db_max_connections
      error_message = "PgBouncer server pools (instances x (app + reports)) plus the direct reserve exceed Postgres max_connections."
    }
  }
}

resource "aws_lb" "pooler" {
  name                             = "${var.name}-pooler"
  load_balancer_type               = "network"
  internal                         = true
  subnets                          = aws_subnet.private[*].id
  enable_cross_zone_load_balancing = true
  tags                             = merge(var.tags, { data_class = "network" })
}

resource "aws_lb_target_group" "pooler" {
  name     = "${var.name}-pooler"
  port     = 6432
  protocol = "TCP"
  vpc_id   = aws_vpc.this.id
  tags     = merge(var.tags, { data_class = "network" })

  health_check {
    protocol            = "TCP"
    interval            = 10
    healthy_threshold   = 2
    unhealthy_threshold = 2
  }
}

resource "aws_lb_listener" "pooler" {
  load_balancer_arn = aws_lb.pooler.arn
  port              = 6432
  protocol          = "TCP"
  tags              = merge(var.tags, { data_class = "network" })

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.pooler.arn
  }
}

resource "aws_autoscaling_group" "pooler" {
  name                = "${var.name}-pgbouncer"
  min_size            = local.pooler_count
  max_size            = local.pooler_count
  desired_capacity    = local.pooler_count
  vpc_zone_identifier = aws_subnet.private[*].id # >= 2 AZs: one instance per zone survives an AZ loss
  target_group_arns   = [aws_lb_target_group.pooler.arn]
  health_check_type   = "ELB"

  launch_template {
    id      = aws_launch_template.pooler.id
    version = aws_launch_template.pooler.latest_version
  }
  instance_refresh {
    strategy = "Rolling"
    preferences { min_healthy_percentage = 50 }
  }
  dynamic "tag" {
    for_each = merge(var.tags, { data_class = "network" })
    content {
      key                 = tag.key
      value               = tag.value
      propagate_at_launch = true
    }
  }
}
