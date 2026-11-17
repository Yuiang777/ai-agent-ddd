package cn.apple.config;

import cn.apple.domain.legal.matter.repository.LegalMatterRepository;
import cn.apple.infrastructure.legal.matter.JdbcLegalMatterRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(name = "app.legal.matter-store", havingValue = "sql")
public class SqlLegalMatterStoreConfig {
    @Bean
    public LegalMatterRepository sqlLegalMatterRepository(@Qualifier("mysqlDataSource") DataSource source,
            ObjectMapper mapper, @Value("${app.legal.matter-schema-initialize:false}") boolean initialize) {
        if (initialize) new ResourceDatabasePopulator(new ClassPathResource("db/legal-matter-mysql.sql")).execute(source);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.queryForList("SELECT tenant_id, matter_id, version, payload FROM legal_matter WHERE 1=0");
        jdbc.queryForList("SELECT tenant_id, task_id, matter_id, version, payload FROM legal_matter_review WHERE 1=0");
        jdbc.queryForList("SELECT tenant_id, matter_id, evidence_id, version, payload FROM legal_matter_evidence WHERE 1=0");
        jdbc.queryForList("SELECT tenant_id, matter_id, deadline_id, payload FROM legal_matter_deadline WHERE 1=0");
        jdbc.queryForList("SELECT sequence_id, tenant_id, matter_id, event_id, payload FROM legal_matter_audit WHERE 1=0");
        return new JdbcLegalMatterRepository(source, mapper);
    }
}
