# Private-only network: no internet gateway, no public subnets. Egress (NAT / endpoints) is added with the container platform (DEC-21).
resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = merge(var.tags, { Name = var.name, data_class = "network" })
}

resource "aws_subnet" "private" {
  count                   = length(var.azs)
  vpc_id                  = aws_vpc.this.id
  availability_zone       = var.azs[count.index]
  cidr_block              = cidrsubnet(var.vpc_cidr, 4, count.index)
  map_public_ip_on_launch = false
  tags                    = merge(var.tags, { Name = "${var.name}-private-${count.index}", data_class = "network", tier = "private" })
}

resource "aws_security_group" "db" {
  name        = "${var.name}-db"
  description = "Postgres: reachable only from inside the cell VPC"
  vpc_id      = aws_vpc.this.id
  tags        = merge(var.tags, { data_class = "network" })

  ingress {
    description = "Postgres from the VPC"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }
}

# OPS-054: restore drills land behind this group. Only members of the same group reach the restored DB, so production cannot.
resource "aws_security_group" "drill" {
  name        = "${var.name}-restore-drill"
  description = "Isolated restore-drill environment"
  vpc_id      = aws_vpc.this.id
  tags        = merge(var.tags, { data_class = "network", purpose = "restore-drill" })

  ingress {
    description = "Postgres within the drill group only"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    self        = true
  }

  egress {
    description = "Within the drill group only"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    self        = true
  }
}
