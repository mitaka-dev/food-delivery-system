# ── CodeArtifact domain ──────────────────────────────────────────────────────

resource "aws_codeartifact_domain" "platform" {
  domain         = "food-delivery-platform"
  encryption_key = aws_kms_key.shared.arn
}

# ── maven-central upstream proxy ─────────────────────────────────────────────
# Declared first so the internal repo can reference it as an upstream.
# Proxies public Apache Maven Central — service builds resolve transitive
# dependencies through CodeArtifact instead of reaching the internet directly.

resource "aws_codeartifact_repository" "maven_central" {
  repository = "maven-central"
  domain     = aws_codeartifact_domain.platform.domain

  external_connections {
    external_connection_name = "public:maven-central"
  }
}

# ── internal repository ───────────────────────────────────────────────────────
# Where platform-bom and common-libs publish.
# Upstream chain: internal → maven-central → public Maven Central, so service
# builds only need a single repository URL in settings.xml.

resource "aws_codeartifact_repository" "internal" {
  repository = "internal"
  domain     = aws_codeartifact_domain.platform.domain

  upstream {
    repository_name = aws_codeartifact_repository.maven_central.repository
  }
}

# ── Outputs ──────────────────────────────────────────────────────────────────

output "codeartifact_domain" {
  description = "CodeArtifact domain name"
  value       = aws_codeartifact_domain.platform.domain
}

output "codeartifact_domain_owner" {
  description = "CodeArtifact domain owner account ID"
  value       = aws_codeartifact_domain.platform.owner
}

output "codeartifact_internal_repo_url" {
  description = "Maven repository URL for use in service settings.xml"
  value       = "https://${aws_codeartifact_domain.platform.domain}-${data.aws_caller_identity.current.account_id}.d.codeartifact.${data.aws_region.current.name}.amazonaws.com/maven/${aws_codeartifact_repository.internal.repository}/"
}
