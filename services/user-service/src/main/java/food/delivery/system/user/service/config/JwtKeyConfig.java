package food.delivery.system.user.service.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterType;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

@Configuration
public class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);

    /**
     * Local / test profile: generate a fresh RSA-2048 key pair on startup.
     * No configuration required — tests and local dev work out of the box.
     */
    @Bean
    @Profile("!production")
    public KeyPair localKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        log.info("JWT RS256 key pair generated (local/test profile)");
        return kp;
    }

    // ── Production AWS client beans ───────────────────────────────────────────

    @Bean
    @Profile("production")
    public SecretsManagerClient secretsManagerClient(@Value("${aws.region}") String region) {
        return SecretsManagerClient.builder()
                .region(Region.of(region))
                .build();
    }

    @Bean
    @Profile("production")
    public SsmClient ssmClient(@Value("${aws.region}") String region) {
        return SsmClient.builder()
                .region(Region.of(region))
                .build();
    }

    /**
     * Production: load RSA private key (PKCS#8 PEM) from Secrets Manager,
     * derive the public key, and publish the public key to SSM Parameter Store
     * so the API Gateway Lambda authorizer can verify tokens.
     */
    @Bean
    @Profile("production")
    public KeyPair productionKeyPair(
            SecretsManagerClient secretsManager,
            SsmClient ssm,
            @Value("${aws.secrets.jwt-key-arn}") String keyArn,
            @Value("${aws.ssm.jwt-public-key-path}") String ssmPath) throws Exception {

        String pem = secretsManager.getSecretValue(r -> r.secretId(keyArn)).secretString();
        KeyPair kp = parsePkcs8PemKeyPair(pem);

        String pubKeyBase64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        ssm.putParameter(r -> r
                .name(ssmPath)
                .value(pubKeyBase64)
                .type(ParameterType.STRING)
                .overwrite(true));

        log.info("JWT RS256 key loaded from Secrets Manager; public key published to SSM at {}", ssmPath);
        return kp;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private KeyPair parsePkcs8PemKeyPair(String pem) throws Exception {
        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        byte[] keyBytes = Base64.getDecoder().decode(base64);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) kf.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        RSAPublicKey publicKey = (RSAPublicKey) kf.generatePublic(
                new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
        return new KeyPair(publicKey, privateKey);
    }
}
