# OPS-056: the only public tier of the cell. Public subnets hold the load balancer and nothing else; Core, worker and BFF stay private.
resource "aws_internet_gateway" "edge" {
  count  = local.edge ? 1 : 0
  vpc_id = aws_vpc.this.id
  tags   = merge(var.tags, { Name = "${var.name}-edge", data_class = "network" })
}

resource "aws_subnet" "edge" {
  count                   = local.edge ? length(var.azs) : 0
  vpc_id                  = aws_vpc.this.id
  availability_zone       = var.azs[count.index]
  cidr_block              = cidrsubnet(var.vpc_cidr, 4, 8 + count.index) # private uses indexes 0..n-1
  map_public_ip_on_launch = false
  tags                    = merge(var.tags, { Name = "${var.name}-edge-${count.index}", data_class = "network", tier = "edge" })
}

resource "aws_route_table" "edge" {
  count  = local.edge ? 1 : 0
  vpc_id = aws_vpc.this.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.edge[0].id
  }
  tags = merge(var.tags, { Name = "${var.name}-edge", data_class = "network" })
}

resource "aws_route_table_association" "edge" {
  count          = local.edge ? length(var.azs) : 0
  subnet_id      = aws_subnet.edge[count.index].id
  route_table_id = aws_route_table.edge[0].id
}

resource "aws_security_group" "alb" {
  count       = local.edge ? 1 : 0
  name        = "${var.name}-alb"
  description = "Public HTTPS in, private VPC out"
  vpc_id      = aws_vpc.this.id
  tags        = merge(var.tags, { Name = "${var.name}-alb", data_class = "network" })

  ingress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    description = "HTTP, only to redirect to HTTPS"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    description = "Targets inside the VPC only"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = [var.vpc_cidr]
  }
}
