CREATE TABLE IF NOT EXISTS legal_matter (
  tenant_id VARCHAR(128) NOT NULL,
  matter_id VARCHAR(128) NOT NULL,
  version BIGINT NOT NULL,
  payload LONGTEXT NOT NULL,
  PRIMARY KEY (tenant_id, matter_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS legal_matter_review (
  tenant_id VARCHAR(128) NOT NULL,
  task_id VARCHAR(128) NOT NULL,
  matter_id VARCHAR(128) NOT NULL,
  version BIGINT NOT NULL,
  payload LONGTEXT NOT NULL,
  PRIMARY KEY (tenant_id, task_id),
  FOREIGN KEY (tenant_id, matter_id) REFERENCES legal_matter(tenant_id, matter_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS legal_matter_evidence (
  tenant_id VARCHAR(128) NOT NULL,
  matter_id VARCHAR(128) NOT NULL,
  evidence_id VARCHAR(128) NOT NULL,
  version INT NOT NULL,
  payload LONGTEXT NOT NULL,
  PRIMARY KEY (tenant_id, matter_id, evidence_id, version),
  FOREIGN KEY (tenant_id, matter_id) REFERENCES legal_matter(tenant_id, matter_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS legal_matter_deadline (
  tenant_id VARCHAR(128) NOT NULL,
  matter_id VARCHAR(128) NOT NULL,
  deadline_id VARCHAR(128) NOT NULL,
  payload LONGTEXT NOT NULL,
  PRIMARY KEY (tenant_id, matter_id, deadline_id),
  FOREIGN KEY (tenant_id, matter_id) REFERENCES legal_matter(tenant_id, matter_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS legal_matter_audit (
  sequence_id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id VARCHAR(128) NOT NULL,
  matter_id VARCHAR(128) NOT NULL,
  event_id VARCHAR(128) NOT NULL,
  payload LONGTEXT NOT NULL,
  PRIMARY KEY (sequence_id),
  UNIQUE (tenant_id, event_id),
  FOREIGN KEY (tenant_id, matter_id) REFERENCES legal_matter(tenant_id, matter_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
