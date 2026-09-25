package com.yizhaoqi.rag.service;

import com.yizhaoqi.rag.exception.CustomException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
public class DocumentSafetyService {

    private final List<String> sensitiveWords;

    public DocumentSafetyService(@Value("${file.upload.sensitive-words:}") String configuredWords) {
        sensitiveWords = Arrays.stream(configuredWords.split(","))
                .map(String::trim)
                .filter(word -> !word.isEmpty())
                .map(word -> word.toLowerCase(Locale.ROOT))
                .toList();
    }

    public void validate(InputStream inputStream) throws IOException, TikaException, SAXException {
        if (sensitiveWords.isEmpty()) {
            return;
        }
        BodyContentHandler handler = new BodyContentHandler(-1);
        new AutoDetectParser().parse(inputStream, handler, new Metadata(), new ParseContext());
        String content = handler.toString().toLowerCase(Locale.ROOT);
        for (String word : sensitiveWords) {
            if (content.contains(word)) {
                throw new CustomException("文件包含禁止上传的敏感内容", HttpStatus.BAD_REQUEST);
            }
        }
    }
}
