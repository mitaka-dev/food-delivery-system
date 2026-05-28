package food.delivery.system.product.service.config;

import food.delivery.system.product.service.grpc.ProductGrpcService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "grpc.server.enabled", matchIfMissing = true)
public class GrpcServerConfig implements SmartLifecycle {

    @Value("${grpc.server.port:9090}")
    private int grpcPort;

    private final ProductGrpcService productGrpcService;
    private Server server;
    private volatile boolean running;

    public GrpcServerConfig(ProductGrpcService productGrpcService) {
        this.productGrpcService = productGrpcService;
    }

    @Override
    public void start() {
        try {
            server = ServerBuilder.forPort(grpcPort)
                    .addService(productGrpcService)
                    .build()
                    .start();
            running = true;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start gRPC server on port " + grpcPort, e);
        }
    }

    @Override
    public void stop(Runnable callback) {
        if (server != null) {
            server.shutdown();
            try {
                server.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        running = false;
        callback.run();
    }

    @Override
    public void stop() {
        stop(() -> {});
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
