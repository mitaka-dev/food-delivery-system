module "msk" {
  source = "../../modules/msk"

  cluster_name       = "food-delivery-production"
  environment        = "production"
  vpc_id             = module.vpc.vpc_id
  private_subnet_ids = module.vpc.private_subnet_ids
  vpc_cidr_block     = module.vpc.vpc_cidr_block

  tags = { Project = "food-delivery" }
}

# Generates scripts/bootstrap-kafka-topics.sh at the repo root.
# Run it once after terraform apply to create the v1 Kafka topics.
resource "local_file" "bootstrap_kafka_topics" {
  filename        = "${path.module}/../../../scripts/bootstrap-kafka-topics.sh"
  file_permission = "0755"

  content = <<-SCRIPT
    #!/usr/bin/env bash
    # Create v1 Kafka topics on MSK Serverless.
    #
    # Prerequisites (run from an EKS pod):
    #   kubectl run kafka-init --rm -it --restart=Never \
    #     --image=bitnami/kafka:3.7 \
    #     --env="BOOTSTRAP_SERVERS=${module.msk.bootstrap_brokers_sasl_iam}" \
    #     -- bash
    #
    # Then inside the pod:
    #   curl -L -o /opt/bitnami/kafka/libs/aws-msk-iam-auth.jar \
    #     https://github.com/aws/aws-msk-iam-auth/releases/latest/download/aws-msk-iam-auth-all.jar
    #   bash bootstrap-kafka-topics.sh

    set -euo pipefail

    : "$${BOOTSTRAP_SERVERS:?Set BOOTSTRAP_SERVERS to the MSK bootstrap endpoint}"

    PROPS_FILE=$$(mktemp)
    trap 'rm -f "$$PROPS_FILE"' EXIT

    cat > "$$PROPS_FILE" <<EOF
    security.protocol=SASL_SSL
    sasl.mechanism=AWS_MSK_IAM
    sasl.jaas.config=software.amazon.msk.auth.iam.IAMLoginModule required;
    sasl.client.callback.handler.class=software.amazon.msk.auth.iam.IAMClientCallbackHandler
    EOF

    create_topic() {
      local topic=$$1 partitions=$$2 retention_ms=$$3
      kafka-topics.sh \
        --bootstrap-server "$$BOOTSTRAP_SERVERS" \
        --command-config "$$PROPS_FILE" \
        --create --if-not-exists \
        --topic "$$topic" \
        --partitions "$$partitions" \
        --replication-factor 3 \
        --config "retention.ms=$$retention_ms"
      echo "  created: $$topic ($$partitions partitions, retention $$(( retention_ms / 86400000 ))d)"
    }

    echo "Creating v1 Kafka topics on $$BOOTSTRAP_SERVERS ..."
    create_topic "user-events"    3  604800000   # 7 days,  key=userId
    create_topic "order-events"   6  1209600000  # 14 days, key=orderId
    create_topic "payment-events" 3  2592000000  # 30 days, key=orderId (audit)
    echo "Done."
  SCRIPT
}

output "msk_bootstrap_brokers" {
  description = "MSK SASL/IAM bootstrap endpoint — use in application-production.yml"
  value       = module.msk.bootstrap_brokers_sasl_iam
}

output "msk_cluster_arn" {
  description = "MSK Serverless cluster ARN"
  value       = module.msk.cluster_arn
}

output "msk_glue_registry_arn" {
  description = "Glue Schema Registry ARN"
  value       = module.msk.glue_registry_arn
}
