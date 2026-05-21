# AWS Account Setup

One-time guide for creating a dedicated AWS account and configuring local tooling for this project.

## 1. Create a new AWS account

Use a separate account — not your personal AWS account — to isolate billing and permissions.

1. Go to [aws.amazon.com](https://aws.amazon.com) → **Create an AWS Account**
2. Use a different email (Gmail plus-addressing works: `you+food-delivery@gmail.com`)
3. Choose the Free tier / personal account type
4. Add a payment method (required even for free tier)
5. Set the default region to **`eu-west-1`** (Ireland) — this matches all Terraform configs

## 2. Create an IAM user for programmatic access

1. Log into the new account → **IAM → Users → Create user**
2. Name: `food-delivery-terraform`
3. Skip console access (CLI only)
4. Attach policy: `AdministratorAccess`
5. After creation → **Security credentials** tab → **Create access key** → choose **CLI**
6. Copy the **Access Key ID** and **Secret Access Key** (shown only once)

## 3. Install asdf

```bash
git clone https://github.com/asdf-vm/asdf.git ~/.asdf --branch v0.14.0
echo '. "$HOME/.asdf/asdf.sh"' >> ~/.bashrc
source ~/.bashrc
```

Add the required plugins:
```bash
asdf plugin add java
asdf plugin add maven
asdf plugin add terraform
asdf plugin add kubectl
asdf plugin add helm
```

## 4. Install project tools via bootstrap-dev.sh

From the repo root:
```bash
./scripts/bootstrap-dev.sh
```

This reads `.tool-versions` and installs Java (Temurin), Maven, Terraform, kubectl, and Helm.

> **Note:** `.tool-versions` uses `temurin-25.x` instead of `corretto-25.x` because
> `corretto.aws` has a `.aws` TLD that some DNS resolvers fail to resolve locally.
> In production, EKS uses Amazon Linux which bundles Corretto — this only affects
> local development tooling.

## 5. Install AWS CLI

```bash
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o /tmp/awscliv2.zip
unzip /tmp/awscliv2.zip -d /tmp
sudo /tmp/aws/install
aws --version
```

## 6. Configure credentials

```bash
aws configure --profile food-delivery-dev
# AWS Access Key ID:     <from step 2>
# AWS Secret Access Key: <from step 2>
# Default region:        eu-west-1
# Default output format: json
```

Set it as the active profile (add to `~/.bashrc` or `.envrc`):
```bash
export AWS_PROFILE=food-delivery-dev
```

Verify:
```bash
aws sts get-caller-identity
# Returns Account, UserId, Arn — all from the new account
```

## 7. Verify Terraform (VPC)

```bash
cd platform-infra/envs/production
terraform init
terraform plan    # should show ~38 resources, no errors
terraform apply   # creates the VPC — takes ~3 min (NAT GW is slow)

aws ec2 describe-vpcs \
  --filters "Name=tag:Environment,Values=production" \
  --region eu-west-1
# Returns the VPC with CIDR 10.0.0.0/16
```
