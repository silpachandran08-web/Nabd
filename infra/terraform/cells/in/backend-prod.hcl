# Names are outputs of bootstrap/aws (not secrets). Replace ACCOUNT_ID after bootstrap.
bucket         = "nabd-tfstate-ACCOUNT_ID-ap-south-1"
key            = "cells/in/prod.tfstate"
region         = "ap-south-1"
dynamodb_table = "nabd-tfstate-lock"
encrypt        = true
kms_key_id     = "alias/nabd-tfstate"
