package com.payflow.infrastructure.io;

import com.payflow.application.dto.TransactionView;
import com.payflow.application.port.CsvExportPort;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

@Slf4j
@Component
public class CsvStatementAdapter implements CsvExportPort {

    @Override
    public void writeCsv(Stream<TransactionView> transactions, OutputStream out) throws IOException {
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(out, StandardCharsets.UTF_8), 1024 * 8);

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader("transaction_id", "entry_type", "amount", "balance_after", "created_at").get();

        try (CSVPrinter printer = new CSVPrinter(writer, format)) {
            transactions.forEach(tx -> {
                try {
                    printer.printRecord(
                            tx.transactionId(),
                            tx.entryType(),
                            formatMoney(tx.amount()),
                            formatMoney(tx.balanceAfter()),
                            tx.createdAt()
                    );

                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            log.info("CSV export completed");
        }
    }

    private String formatMoney(long minorUnits) {
        return BigDecimal.valueOf(minorUnits, 2).toPlainString();
    }
}
