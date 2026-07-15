package dev.ragplatform.infrastructure.extraction;

import dev.ragplatform.domain.port.out.TextExtractor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * Extrai texto de PDFs usando Apache PDFBox 3.x.
 * Texto nativo apenas — OCR para PDFs escaneados será adicionado na Fatia 2.3.
 */
@Component
public class PdfExtractor implements TextExtractor {

    @Override
    public boolean supports(String mimeType) {
        return "application/pdf".equals(mimeType);
    }

    @Override
    public String extract(InputStream content, String originalName) throws IOException {
        try (PDDocument doc = Loader.loadPDF(content.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }
}
