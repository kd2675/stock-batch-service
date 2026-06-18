package stock.batch.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@EnableFeignClients
@EnableDiscoveryClient
public class StockBatchServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockBatchServiceApplication.class, args);
    }

}
