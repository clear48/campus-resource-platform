-- @allow_missing=1：迁移前允许目标对象不存在，但同名错误对象必须失败。
-- @allow_missing=0：迁移后目标对象必须完整存在。
DELIMITER $$
DROP PROCEDURE IF EXISTS deploy04_assert_true$$
CREATE PROCEDURE deploy04_assert_true(IN p_ok BOOLEAN, IN p_message VARCHAR(255))
BEGIN
  IF NOT COALESCE(p_ok, FALSE) THEN
    SELECT CONCAT('DEPLOY04_ASSERT_FAIL:', p_message) AS deploy04_assertion_failure;
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = p_message;
  END IF;
END$$

DROP PROCEDURE IF EXISTS deploy04_assert_schema$$
CREATE PROCEDURE deploy04_assert_schema()
BEGIN
  DECLARE v_allow_missing BOOLEAN DEFAULT COALESCE(@allow_missing, 0);
  DECLARE v_exists INT DEFAULT 0;
  DECLARE v_mismatch INT DEFAULT 0;

  DROP TEMPORARY TABLE IF EXISTS deploy04_expected_columns;
  CREATE TEMPORARY TABLE deploy04_expected_columns (
    table_name VARCHAR(64), ordinal_position INT, column_name VARCHAR(64), column_type VARCHAR(255),
    is_nullable VARCHAR(3), column_default VARCHAR(255), extra VARCHAR(255), PRIMARY KEY(table_name, column_name)
  );
  INSERT INTO deploy04_expected_columns VALUES
    ('download_delta_sync_item',1,'id','bigint','NO',NULL,'auto_increment'),
    ('download_delta_sync_item',2,'batch_id','char(36)','NO',NULL,''),
    ('download_delta_sync_item',3,'resource_id','bigint','NO',NULL,''),
    ('download_delta_sync_item',4,'delta','bigint','NO',NULL,''),
    ('download_delta_sync_item',5,'confirmed_at','datetime','YES',NULL,''),
    ('download_delta_sync_item',6,'created_at','datetime','NO','CURRENT_TIMESTAMP','DEFAULT_GENERATED'),
    ('download_delta_sync_item',7,'updated_at','datetime','NO','CURRENT_TIMESTAMP','DEFAULT_GENERATED on update CURRENT_TIMESTAMP'),
    ('user_file_authorization',1,'id','bigint','NO',NULL,'auto_increment'),
    ('user_file_authorization',2,'user_id','bigint','NO',NULL,''),
    ('user_file_authorization',3,'file_id','bigint','NO',NULL,''),
    ('user_file_authorization',4,'source_type','tinyint','NO',NULL,''),
    ('user_file_authorization',5,'created_at','datetime','NO','CURRENT_TIMESTAMP','DEFAULT_GENERATED');

  DROP TEMPORARY TABLE IF EXISTS deploy04_expected_indexes;
  CREATE TEMPORARY TABLE deploy04_expected_indexes (
    table_name VARCHAR(64), index_name VARCHAR(64), non_unique INT, seq_in_index INT, column_name VARCHAR(64),
    PRIMARY KEY(table_name,index_name,seq_in_index)
  );
  INSERT INTO deploy04_expected_indexes VALUES
    ('download_delta_sync_item','PRIMARY',0,1,'id'),
    ('download_delta_sync_item','uk_download_delta_sync_batch_resource',0,1,'batch_id'),
    ('download_delta_sync_item','uk_download_delta_sync_batch_resource',0,2,'resource_id'),
    ('download_delta_sync_item','idx_download_delta_sync_confirmed_created',1,1,'confirmed_at'),
    ('download_delta_sync_item','idx_download_delta_sync_confirmed_created',1,2,'created_at'),
    ('user_file_authorization','PRIMARY',0,1,'id'),
    ('user_file_authorization','uk_user_file_authorization',0,1,'user_id'),
    ('user_file_authorization','uk_user_file_authorization',0,2,'file_id'),
    ('user_file_authorization','idx_file_authorized_user',1,1,'file_id'),
    ('user_file_authorization','idx_file_authorized_user',1,2,'user_id');

  BEGIN
    DECLARE done INT DEFAULT 0;
    DECLARE t VARCHAR(64);
    DECLARE cur CURSOR FOR SELECT 'download_delta_sync_item' UNION ALL SELECT 'user_file_authorization';
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;
    OPEN cur;
    table_loop: LOOP
      FETCH cur INTO t;
      IF done THEN LEAVE table_loop; END IF;
      SELECT COUNT(*) INTO v_mismatch FROM information_schema.tables
       WHERE table_schema=DATABASE() AND table_name=t;
      SELECT COUNT(*) INTO v_exists FROM information_schema.tables
       WHERE table_schema=DATABASE() AND table_name=t AND table_type='BASE TABLE';
      IF v_exists = 0 THEN
        CALL deploy04_assert_true(v_mismatch=0, CONCAT('required object is not base table: ', t));
        CALL deploy04_assert_true(v_allow_missing, CONCAT('missing required table: ', t));
      ELSE
        SELECT COUNT(*) INTO v_mismatch FROM information_schema.tables
         WHERE table_schema=DATABASE() AND table_name=t
           AND (engine <> 'InnoDB' OR table_collation <> 'utf8mb4_0900_ai_ci');
        CALL deploy04_assert_true(v_mismatch=0, CONCAT('wrong engine/collation: ', t));

        SELECT COUNT(*) INTO v_mismatch
          FROM deploy04_expected_columns e LEFT JOIN information_schema.columns c
            ON c.table_schema=DATABASE() AND c.table_name=e.table_name AND c.column_name=e.column_name
         WHERE e.table_name=t AND (c.column_name IS NULL OR c.ordinal_position<>e.ordinal_position
           OR LOWER(c.column_type)<>e.column_type OR c.is_nullable<>e.is_nullable
           OR NOT (c.column_default <=> e.column_default) OR c.extra<>e.extra);
        SELECT v_mismatch + COUNT(*) INTO v_mismatch
          FROM information_schema.columns c LEFT JOIN deploy04_expected_columns e
            ON e.table_name=c.table_name AND e.column_name=c.column_name
         WHERE c.table_schema=DATABASE() AND c.table_name=t AND e.column_name IS NULL;
        CALL deploy04_assert_true(v_mismatch=0, CONCAT('wrong column definition: ', t));

        SELECT COUNT(*) INTO v_mismatch FROM deploy04_expected_indexes e LEFT JOIN information_schema.statistics s
          ON s.table_schema=DATABASE() AND s.table_name=e.table_name AND s.index_name=e.index_name
           AND s.seq_in_index=e.seq_in_index
         WHERE e.table_name=t AND (s.index_name IS NULL OR s.non_unique<>e.non_unique OR s.column_name<>e.column_name);
        SELECT v_mismatch + COUNT(*) INTO v_mismatch
          FROM information_schema.statistics s LEFT JOIN deploy04_expected_indexes e
            ON e.table_name=s.table_name AND e.index_name=s.index_name AND e.seq_in_index=s.seq_in_index
         WHERE s.table_schema=DATABASE() AND s.table_name=t AND e.index_name IS NULL;
        CALL deploy04_assert_true(v_mismatch=0, CONCAT('wrong index definition: ', t));

        SELECT COUNT(*) INTO v_mismatch FROM information_schema.referential_constraints
         WHERE constraint_schema=DATABASE() AND table_name=t;
        CALL deploy04_assert_true(v_mismatch=0, CONCAT('foreign key is forbidden: ', t));
      END IF;
    END LOOP;
    CLOSE cur;
  END;

  SELECT COUNT(*) INTO v_exists FROM information_schema.tables
   WHERE table_schema=DATABASE() AND table_name='download_delta_sync_item';
  IF v_exists=1 THEN
    SELECT COUNT(*) INTO v_mismatch FROM information_schema.table_constraints
     WHERE constraint_schema=DATABASE() AND table_name='download_delta_sync_item' AND constraint_type='CHECK';
    CALL deploy04_assert_true(v_mismatch=1, 'wrong download delta CHECK count');
    SELECT COUNT(*) INTO v_mismatch
      FROM information_schema.table_constraints tc JOIN information_schema.check_constraints cc
        ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name
     WHERE tc.constraint_schema=DATABASE() AND tc.table_name='download_delta_sync_item'
       AND tc.constraint_type='CHECK' AND tc.constraint_name='chk_download_delta_sync_item_delta'
       AND tc.enforced='YES'
       AND LOWER(REGEXP_REPLACE(cc.check_clause,'[` ()]',''))='delta>0';
    CALL deploy04_assert_true(v_mismatch=1, 'wrong download delta CHECK');
  END IF;

  SELECT COUNT(*) INTO v_exists FROM information_schema.tables
   WHERE table_schema=DATABASE() AND table_name='user_file_authorization';
  IF v_exists=1 THEN
    SELECT COUNT(*) INTO v_mismatch FROM information_schema.table_constraints
     WHERE constraint_schema=DATABASE() AND table_name='user_file_authorization' AND constraint_type='CHECK';
    CALL deploy04_assert_true(v_mismatch=1, 'wrong authorization CHECK count');
    SELECT COUNT(*) INTO v_mismatch
      FROM information_schema.table_constraints tc JOIN information_schema.check_constraints cc
        ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name
     WHERE tc.constraint_schema=DATABASE() AND tc.table_name='user_file_authorization'
       AND tc.constraint_type='CHECK' AND tc.constraint_name='chk_file_authorization_source'
       AND tc.enforced='YES'
       AND LOWER(REGEXP_REPLACE(cc.check_clause,'[` ()]','')) IN ('source_typein(1,2,3)','source_typein1,2,3');
    CALL deploy04_assert_true(v_mismatch=1, 'wrong authorization CHECK');
  END IF;

  SELECT COUNT(*) INTO v_exists FROM information_schema.tables
   WHERE table_schema=DATABASE() AND table_name='resource' AND table_type='BASE TABLE';
  CALL deploy04_assert_true(v_exists=1, 'legacy resource base table missing');
  SELECT COUNT(*) INTO v_exists FROM information_schema.columns
   WHERE table_schema=DATABASE() AND table_name='resource' AND column_name='active_duplicate_guard';
  IF v_exists=0 THEN
    -- 部分 DDL 失败后可能只留下同名错误索引；即使允许目标列缺失也必须独立拒绝。
    SELECT COUNT(*) INTO v_mismatch FROM information_schema.statistics
     WHERE table_schema=DATABASE() AND table_name='resource'
       AND index_name='uk_resource_active_duplicate';
    CALL deploy04_assert_true(v_mismatch=0, 'active duplicate index exists without guard column');
    CALL deploy04_assert_true(v_allow_missing, 'active_duplicate_guard missing');
  ELSE
    SELECT COUNT(*) INTO v_mismatch FROM information_schema.columns
     WHERE table_schema=DATABASE() AND table_name='resource' AND column_name='active_duplicate_guard'
       AND LOWER(column_type)='tinyint' AND is_nullable='YES' AND column_default IS NULL
       AND extra='STORED GENERATED'
       AND LOWER(REGEXP_REPLACE(generation_expression,'[` ()]',''))='casewhenstatusin0,1then1elsenullend';
    CALL deploy04_assert_true(v_mismatch=1, 'wrong active_duplicate_guard definition');
    SELECT COUNT(*) INTO v_mismatch FROM information_schema.statistics
     WHERE table_schema=DATABASE() AND table_name='resource'
       AND index_name='uk_resource_active_duplicate' AND non_unique=0
       AND ((seq_in_index=1 AND column_name='uploader_id') OR
            (seq_in_index=2 AND column_name='file_id') OR
            (seq_in_index=3 AND column_name='active_duplicate_guard'));
    CALL deploy04_assert_true(v_mismatch=3, 'wrong active duplicate unique index');
    SELECT COUNT(*) INTO v_mismatch FROM information_schema.statistics
     WHERE table_schema=DATABASE() AND table_name='resource' AND index_name='uk_resource_active_duplicate';
    CALL deploy04_assert_true(v_mismatch=3, 'wrong active duplicate unique index width');
    SELECT COUNT(*) INTO v_mismatch FROM resource
     WHERE NOT (active_duplicate_guard <=> CASE WHEN status IN (0,1) THEN 1 ELSE NULL END);
    CALL deploy04_assert_true(v_mismatch=0, 'wrong active_duplicate_guard data semantics');
  END IF;

  IF NOT v_allow_missing THEN
    SELECT COUNT(*) INTO v_mismatch FROM (
      SELECT uploader_id,file_id FROM resource WHERE status IN (0,1)
      GROUP BY uploader_id,file_id HAVING COUNT(*)>1
    ) d;
    CALL deploy04_assert_true(v_mismatch=0, 'active duplicate group exists');
    SELECT COUNT(*) INTO v_mismatch FROM file_info f LEFT JOIN user_file_authorization a
      ON a.user_id=f.uploader_id AND a.file_id=f.id AND a.source_type=3 WHERE a.id IS NULL;
    CALL deploy04_assert_true(v_mismatch=0, 'first uploader authorization backfill incomplete');
    SELECT COUNT(*) INTO v_mismatch FROM resource r JOIN file_info f ON f.id=r.file_id
      LEFT JOIN user_file_authorization a ON a.user_id=r.uploader_id AND a.file_id=r.file_id
     WHERE r.uploader_id<>f.uploader_id AND a.id IS NOT NULL;
    CALL deploy04_assert_true(v_mismatch=0, 'cross-user historical reference became authorized');
    SELECT COUNT(*) INTO v_mismatch FROM resource r JOIN file_info f ON f.id=r.file_id
     WHERE r.uploader_id<>f.uploader_id;
    CALL deploy04_assert_true(v_mismatch>0, 'cross-user historical reference missing');
  END IF;
END$$
DELIMITER ;

CALL deploy04_assert_schema();
DROP PROCEDURE deploy04_assert_schema;
DROP PROCEDURE deploy04_assert_true;
