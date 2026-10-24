package com.yizhaoqi.rag.repository;

import com.yizhaoqi.rag.chunker.DocumentType;
import com.yizhaoqi.rag.model.PrivateDocumentVector;
import com.yizhaoqi.rag.model.PublicDocumentVector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证公开法典与私人案件分块确实落在不同的 MySQL 表。
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:vector-isolation;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false"
})
class DocumentVectorPersistenceTest {

    @Autowired
    private DocumentVectorRepository publicRepository;

    @Autowired
    private PrivateDocumentVectorRepository privateRepository;

    @Test
    void publicAndPrivateVectorsAreStoredInSeparateTables() {
        PublicDocumentVector publicVector = new PublicDocumentVector();
        publicVector.setFileMd5("public-md5");
        publicVector.setChunkId(1);
        publicVector.setTextContent("公开法典分块");
        publicVector.setUserId("1");
        publicVector.setPublic(true);
        publicVector.setDocumentType(DocumentType.STRUCTURED_LAW.name());
        publicRepository.save(publicVector);

        PrivateDocumentVector privateVector = new PrivateDocumentVector();
        privateVector.setFileMd5("private-md5");
        privateVector.setChunkId(1);
        privateVector.setTextContent("私人案件分块");
        privateVector.setUserId("1");
        privateVector.setPublic(false);
        privateVector.setDocumentType(DocumentType.PRIVATE_LEGAL.name());
        privateRepository.save(privateVector);

        assertEquals(1, publicRepository.count());
        assertEquals(1, privateRepository.count());
        assertEquals(0, publicRepository.findByFileMd5("private-md5").size());
        assertEquals(0, privateRepository.findByFileMd5("public-md5").size());
    }
}
