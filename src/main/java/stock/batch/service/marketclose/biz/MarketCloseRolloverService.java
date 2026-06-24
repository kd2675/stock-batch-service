package stock.batch.service.marketclose.biz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import stock.batch.service.batch.marketclose.writer.MarketCloseRolloverWriter;

@Service
@RequiredArgsConstructor
public class MarketCloseRolloverService {

    private final MarketCloseRolloverWriter writer;

    @Transactional
    public int rolloverClosingPrices() {
        return writer.rolloverClosingPrices();
    }
}
