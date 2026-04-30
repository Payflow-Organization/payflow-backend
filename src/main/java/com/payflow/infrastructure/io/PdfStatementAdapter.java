package com.payflow.infrastructure.io;

import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.UnitValue;
import com.payflow.application.dto.TransactionView;
import com.payflow.application.port.PdfExportPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class PdfStatementAdapter implements PdfExportPort {
    @Override
    public void writePdf(UUID walletId, Stream<TransactionView> transactions, OutputStream out) {
        List<TransactionView> rows = transactions.toList();

        try (PdfDocument pdf = new PdfDocument(new PdfWriter(out))) {
            Document document = new Document(pdf, PageSize.A4);
            document.add(header(walletId));
            document.add(transactionTable(rows));
            document.add(footer(rows));
            log.info("PDF export completed pages={} walletId={}", pdf.getNumberOfPages(), walletId);        }
    }

    private Paragraph header(UUID walletId) {
        return new Paragraph()
                .setMarginBottom(4)
                .add(new Paragraph("PayFlow — Wallet Statement")
                        .setFontSize(18)
                        .simulateBold())
                .add(new Paragraph("Wallet: " + walletId.toString())
                        .setFontSize(10)
                        .simulateBold());
    }

    private Table transactionTable(List<TransactionView> transactions){
        Table table = new Table(UnitValue
                .createPercentArray(new float[]{20F, 30F, 25F, 25F}))
                .useAllAvailableWidth();
        Stream.of("Date","Description","Amount","Balance")
                .forEach(header -> table.addHeaderCell(
                        new Cell()
                                .add(new Paragraph(header)
                                        .simulateBold().setBackgroundColor(
                                                ColorConstants.LIGHT_GRAY
                                        ))
                ));
        for(TransactionView txView: transactions)
        {
            table.addCell(txView.createdAt().toString());
            table.addCell(txView.entryType().toString());
            table.addCell(formatAmount(txView.amount()));
            table.addCell(formatAmount(txView.balanceAfter()));

        }
        return table;
    }

    private Paragraph footer(List<TransactionView> transactions) {
        var total = transactions.stream()
                .mapToLong(TransactionView::amount)
                .sum();

        return new Paragraph("Total: " + formatAmount(total))
                .simulateBold()
                .setMarginTop(12);
    }

    private String formatAmount(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }
}
