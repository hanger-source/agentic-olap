CREATE TABLE warehouse_data_source (
  id varchar primary key,
  display_name varchar not null,
  status varchar not null,
  created_by varchar not null,
  created_at timestamp not null,
  unique(display_name)
);

CREATE TABLE warehouse_source_binding (
  data_source_id varchar not null REFERENCES warehouse_data_source(id),
  source_identity_hash varchar not null,
  configuration_json text not null,
  created_by varchar not null,
  created_at timestamp not null,
  PRIMARY KEY(data_source_id, source_identity_hash)
);

CREATE TABLE physical_table (
  id varchar primary key,
  code varchar not null,
  created_by varchar not null,
  created_at timestamp not null,
  unique(code)
);

CREATE TABLE physical_column (
  id varchar primary key,
  physical_table_id varchar not null,
  created_by varchar not null,
  created_at timestamp not null
);

CREATE TABLE physical_table_version (
  id varchar primary key,
  physical_table_id varchar not null,
  version bigint not null,
  version_status varchar not null,
  content_proto text not null,
  diagnostic_json text,
  source_version bigint,
  created_by varchar not null,
  created_at timestamp not null,
  activated_by varchar,
  activated_at timestamp,
  deprecated_by varchar,
  deprecated_at timestamp,
  archived_by varchar,
  archived_at timestamp,
  unique(physical_table_id, version),
  active_identity varchar
    GENERATED ALWAYS AS (CASE WHEN version_status = 'ACTIVE' THEN physical_table_id ELSE NULL END)
);

CREATE TABLE physical_column_version (
  id varchar primary key,
  physical_column_id varchar not null,
  version bigint not null,
  version_status varchar not null,
  content_proto text not null,
  diagnostic_json text,
  source_version bigint,
  created_by varchar not null,
  created_at timestamp not null,
  activated_by varchar,
  activated_at timestamp,
  deprecated_by varchar,
  deprecated_at timestamp,
  archived_by varchar,
  archived_at timestamp,
  unique(physical_column_id, version),
  active_identity varchar
    GENERATED ALWAYS AS (CASE WHEN version_status = 'ACTIVE' THEN physical_column_id ELSE NULL END)
);

CREATE TABLE warehouse_discovery_snapshot (
    structure_provenance_proto TEXT,
  id varchar primary key,
  data_source varchar not null,
  dialect_id varchar not null,
  catalog_name varchar not null,
  schema_name varchar not null,
  database_product_name varchar,
  database_product_version varchar,
  driver_name varchar,
  driver_version varchar,
  read_only boolean,
  status varchar not null,
  table_count int not null,
  error_message text,
  requested_by varchar not null,
  requested_at timestamp not null,
  discovered_at timestamp not null,
  source_identity_hash varchar not null,
  FOREIGN KEY(data_source, source_identity_hash)
    REFERENCES warehouse_source_binding(data_source_id, source_identity_hash),
  unique(data_source, catalog_name, schema_name, requested_at)
);

CREATE TABLE warehouse_discovered_table (
  id varchar primary key,
  discovery_snapshot_id varchar not null,
  catalog_name varchar not null,
  schema_name varchar not null,
  table_name varchar not null,
  table_comment text,
  row_count bigint,
  schema_hash varchar,
  registered_physical_table_id varchar,
  status varchar not null,
  created_at timestamp not null,
  constraints_proto text,
  unique(discovery_snapshot_id, catalog_name, schema_name, table_name)
);

CREATE TABLE warehouse_discovered_column (
  id varchar primary key,
  discovered_table_id varchar not null,
  column_name varchar not null,
  jdbc_type int not null,
  type_name varchar not null,
  precision_value int,
  scale_value int,
  auto_increment boolean,
  generated boolean,
  default_value text,
  column_comment text,
  nullable boolean,
  ordinal_position int,
  created_at timestamp not null,
  unique(discovered_table_id, column_name)
);

CREATE TABLE warehouse_discovered_unique_key (
    id varchar PRIMARY KEY,
    discovered_table_id varchar NOT NULL REFERENCES warehouse_discovered_table(id),
    rule_name varchar NOT NULL,
    proof_proto text NOT NULL,
    UNIQUE(discovered_table_id, rule_name)
);

CREATE TABLE warehouse_source_collection (
    id varchar PRIMARY KEY,
    data_source_id varchar NOT NULL REFERENCES warehouse_data_source(id),
    source_identity_hash varchar NOT NULL,
    declaration_sha256 varchar NOT NULL,
    collection_code varchar NOT NULL,
    record_proto text NOT NULL,
    FOREIGN KEY(data_source_id, source_identity_hash)
      REFERENCES warehouse_source_binding(data_source_id, source_identity_hash),
    UNIQUE(data_source_id, source_identity_hash, declaration_sha256, collection_code)
);

CREATE TABLE physical_column_profile (
  id varchar primary key,
  physical_column_id varchar not null,
  physical_column_version bigint not null,
  profile_json text not null,
  sample_values_json text,
  null_rate double,
  distinct_count bigint,
  min_value varchar,
  max_value varchar,
  profiled_at timestamp not null,
  status varchar not null,
  error_message text,
  created_by varchar not null,
  created_at timestamp not null,
  unique(physical_column_id, physical_column_version)
);

CREATE TABLE warehouse_source_adoption (
  id varchar PRIMARY KEY,
  physical_table_id varchar NOT NULL,
  physical_table_version bigint NOT NULL,
  data_source_id varchar NOT NULL,
  source_identity_hash varchar NOT NULL,
  discovery_snapshot_id varchar NOT NULL REFERENCES warehouse_discovery_snapshot(id),
  discovered_table_id varchar NOT NULL REFERENCES warehouse_discovered_table(id),
  record_proto text NOT NULL,
  FOREIGN KEY(physical_table_id, physical_table_version) REFERENCES physical_table_version(physical_table_id, version),
  FOREIGN KEY(data_source_id, source_identity_hash) REFERENCES warehouse_source_binding(data_source_id, source_identity_hash)
);

CREATE INDEX ix_source_adoption_snapshot ON warehouse_source_adoption(discovery_snapshot_id, physical_table_id, physical_table_version);

CREATE TABLE logical_table (
  id varchar primary key,
  code varchar not null,
  created_by varchar not null,
  created_at timestamp not null,
  unique(code)
);

CREATE TABLE physical_logical_table_ref (
  id varchar primary key,
  physical_table_id varchar not null,
  physical_table_version bigint not null,
  logical_table_id varchar not null,
  logical_table_version bigint not null,
  ref_role varchar not null,
  created_at timestamp not null,
  unique(physical_table_id, physical_table_version, logical_table_id, logical_table_version, ref_role)
);

CREATE TABLE logical_table_version (
  id varchar primary key,
  logical_table_id varchar not null,
  version bigint not null,
  version_status varchar not null,
  content_proto text not null,
  diagnostic_json text,
  source_version bigint,
  created_by varchar not null,
  created_at timestamp not null,
  activated_by varchar,
  activated_at timestamp,
  deprecated_by varchar,
  deprecated_at timestamp,
  archived_by varchar,
  archived_at timestamp,
  unique(logical_table_id, version),
  active_identity varchar
    GENERATED ALWAYS AS (CASE WHEN version_status = 'ACTIVE' THEN logical_table_id ELSE NULL END)
);

CREATE TABLE logic_unit (
  id varchar primary key,
  logical_table_id varchar not null,
  code varchar not null,
  created_by varchar not null,
  created_at timestamp not null,
  unique(logical_table_id, code),
  unique(logical_table_id, id)
);

CREATE TABLE logic_unit_version (
  id varchar primary key,
  logic_unit_id varchar not null,
  version bigint not null,
  version_status varchar not null,
  content_proto text not null,
  diagnostic_json text,
  source_version bigint,
  created_by varchar not null,
  created_at timestamp not null,
  activated_by varchar,
  activated_at timestamp,
  deprecated_by varchar,
  deprecated_at timestamp,
  archived_by varchar,
  archived_at timestamp,
  unique(logic_unit_id, version),
  active_identity varchar
    GENERATED ALWAYS AS (CASE WHEN version_status = 'ACTIVE' THEN logic_unit_id ELSE NULL END)
);

CREATE TABLE logic_unit_source_ref (
  id varchar primary key,
  logic_unit_id varchar not null,
  logic_unit_version bigint not null,
  physical_column_id varchar not null,
  physical_column_version bigint not null,
  ref_role varchar not null check (ref_role IN ('DIRECT_SOURCE', 'EXPRESSION_DEPENDENCY')),
  created_at timestamp not null,
  unique(logic_unit_id, logic_unit_version, physical_column_id, ref_role)
);

CREATE TABLE logical_table_logic_unit_ref (
  id varchar primary key,
  logical_table_id varchar not null,
  logical_table_version bigint not null check (logical_table_version > 0),
  logic_unit_id varchar not null,
  logic_unit_version bigint not null check (logic_unit_version > 0),
  created_at timestamp not null,
  unique(logical_table_id, logical_table_version, logic_unit_id),
  unique(logic_unit_id, logic_unit_version),
  foreign key (logical_table_id, logical_table_version)
    references logical_table_version(logical_table_id, version),
  foreign key (logic_unit_id, logic_unit_version)
    references logic_unit_version(logic_unit_id, version),
  foreign key (logical_table_id, logic_unit_id)
    references logic_unit(logical_table_id, id)
);

CREATE TABLE topology (
  id varchar primary key,
  code varchar not null,
  topology_implementation_namespace_code varchar not null,
  created_by varchar not null,
  created_at timestamp not null,
  unique(topology_implementation_namespace_code, code)
);

CREATE TABLE topology_version (
  id varchar primary key,
  topology_id varchar not null,
  version bigint not null,
  version_status varchar not null,
  content_proto text not null,
  diagnostic_json text,
  source_version bigint,
  created_by varchar not null,
  created_at timestamp not null,
  activated_by varchar,
  activated_at timestamp,
  deprecated_by varchar,
  deprecated_at timestamp,
  archived_by varchar,
  archived_at timestamp,
  unique(topology_id, version),
  active_identity varchar
    GENERATED ALWAYS AS (CASE WHEN version_status = 'ACTIVE' THEN topology_id ELSE NULL END)
);

CREATE TABLE topology_logical_table_ref (
  id varchar primary key,
  topology_id varchar not null,
  topology_version bigint not null,
  logical_table_id varchar not null,
  logical_table_version bigint not null,
  usage_role varchar not null,
  created_at timestamp not null,
  unique(topology_id, topology_version, logical_table_id, logical_table_version, usage_role)
);

CREATE TABLE topology_join_path_ref (
  id varchar primary key,
  topology_id varchar not null,
  topology_version bigint not null,
  join_path_id varchar not null,
  join_path_version bigint not null,
  created_at timestamp not null,
  unique(topology_id, topology_version, join_path_id)
);

CREATE TABLE join_path (
  id varchar primary key,
  topology_id varchar not null,
  code varchar not null,
  created_by varchar not null,
  created_at timestamp not null
);

CREATE TABLE join_path_version (
  id varchar primary key,
  join_path_id varchar not null,
  version bigint not null,
  version_status varchar not null,
  content_proto text not null,
  join_type varchar not null,
  cardinality varchar not null,
  temporal_config_proto text,
  null_policy varchar,
  diagnostic_json text,
  source_version bigint,
  created_by varchar not null,
  created_at timestamp not null,
  activated_by varchar,
  activated_at timestamp,
  deprecated_by varchar,
  deprecated_at timestamp,
  archived_by varchar,
  archived_at timestamp,
  unique(join_path_id, version),
  active_identity varchar
    GENERATED ALWAYS AS (CASE WHEN version_status = 'ACTIVE' THEN join_path_id ELSE NULL END)
);

CREATE TABLE join_path_key_ref (
  id varchar primary key,
  join_path_id varchar not null,
  join_path_version bigint not null,
  key_order int not null,
  source_logic_unit_id varchar not null,
  source_logic_unit_version bigint not null,
  target_logic_unit_id varchar not null,
  target_logic_unit_version bigint not null,
  created_at timestamp not null,
  unique(join_path_id, join_path_version, key_order)
);

CREATE TABLE event (
  id varchar primary key,
  code varchar not null,
  topology_id varchar not null,
  created_by varchar not null,
  created_at timestamp not null,
  unique(topology_id, code)
);

CREATE TABLE event_version (
  id varchar primary key,
  event_id varchar not null,
  version bigint not null,
  version_status varchar not null,
  content_proto text not null,
  diagnostic_json text,
  source_version bigint,
  created_by varchar not null,
  created_at timestamp not null,
  activated_by varchar,
  activated_at timestamp,
  deprecated_by varchar,
  deprecated_at timestamp,
  archived_by varchar,
  archived_at timestamp,
  unique(event_id, version),
  active_identity varchar
    GENERATED ALWAYS AS (CASE WHEN version_status = 'ACTIVE' THEN event_id ELSE NULL END)
);

CREATE TABLE event_logic_unit_ref (
  id varchar primary key,
  event_id varchar not null,
  event_version bigint not null,
  logic_unit_id varchar not null,
  logic_unit_version bigint not null,
  ref_role varchar not null,
  created_at timestamp not null,
  unique(event_id, event_version, logic_unit_id, logic_unit_version, ref_role)
);

CREATE TABLE event_slice_filter (
  id varchar primary key,
  event_id varchar not null,
  event_version bigint not null,
  predicate_proto text not null,
  created_at timestamp not null
);

CREATE TABLE metric (
  id varchar primary key,
  code varchar not null,
  created_by varchar not null,
  created_at timestamp not null
);

CREATE TABLE metric_version (
  id varchar primary key,
  metric_id varchar not null,
  version bigint not null,
  version_status varchar not null,
  content_proto text not null,
  diagnostic_json text,
  source_version bigint,
  created_by varchar not null,
  created_at timestamp not null,
  activated_by varchar,
  activated_at timestamp,
  deprecated_by varchar,
  deprecated_at timestamp,
  archived_by varchar,
  archived_at timestamp,
  unique(metric_id, version),
  active_identity varchar
    GENERATED ALWAYS AS (CASE WHEN version_status = 'ACTIVE' THEN metric_id ELSE NULL END)
);

CREATE TABLE dimension (
  id varchar primary key,
  code varchar not null,
  dimension_code_namespace varchar not null,
  local_code varchar not null,
  created_by varchar not null,
  created_at timestamp not null,
  unique(code),
  unique(dimension_code_namespace, local_code)
);

CREATE TABLE dimension_version (
  id varchar primary key,
  dimension_id varchar not null,
  version bigint not null,
  version_status varchar not null,
  content_proto text not null,
  diagnostic_json text,
  source_version bigint,
  created_by varchar not null,
  created_at timestamp not null,
  activated_by varchar,
  activated_at timestamp,
  deprecated_by varchar,
  deprecated_at timestamp,
  archived_by varchar,
  archived_at timestamp,
  unique(dimension_id, version),
  active_identity varchar
    GENERATED ALWAYS AS (CASE WHEN version_status = 'ACTIVE' THEN dimension_id ELSE NULL END)
);

CREATE TABLE dimension_event_binding (
  id varchar primary key,
  dimension_id varchar not null,
  created_by varchar not null,
  created_at timestamp not null
);

CREATE TABLE analysis_dimension (
  id varchar primary key,
  subject_area_id varchar not null,
  code varchar not null,
  created_by varchar not null,
  created_at timestamp not null,
  unique(subject_area_id, code)
);

CREATE TABLE analysis_dimension_version (
  id varchar primary key,
  analysis_dimension_id varchar not null,
  version bigint not null,
  version_status varchar not null,
  content_proto text not null,
  diagnostic_json text,
  source_version bigint,
  created_by varchar not null,
  created_at timestamp not null,
  activated_by varchar,
  activated_at timestamp,
  deprecated_by varchar,
  deprecated_at timestamp,
  archived_by varchar,
  archived_at timestamp,
  unique(analysis_dimension_id, version),
  active_identity varchar
    GENERATED ALWAYS AS (CASE WHEN version_status = 'ACTIVE' THEN analysis_dimension_id ELSE NULL END)
);

CREATE TABLE scenario (
  id varchar primary key,
  subject_area_id varchar not null,
  code varchar not null,
  created_by varchar not null,
  created_at timestamp not null,
  unique(subject_area_id, code)
);

CREATE TABLE scenario_version (
  id varchar primary key,
  scenario_id varchar not null,
  version bigint not null,
  version_status varchar not null,
  content_proto text not null,
  diagnostic_json text,
  source_version bigint,
  created_by varchar not null,
  created_at timestamp not null,
  activated_by varchar,
  activated_at timestamp,
  deprecated_by varchar,
  deprecated_at timestamp,
  archived_by varchar,
  archived_at timestamp,
  unique(scenario_id, version),
  active_identity varchar
    GENERATED ALWAYS AS (CASE WHEN version_status = 'ACTIVE' THEN scenario_id ELSE NULL END)
);

CREATE TABLE subject_area (
  id varchar primary key,
  code varchar not null,
  business_line_id varchar not null,
  created_by varchar not null,
  created_at timestamp not null,
  unique(business_line_id, code)
);

CREATE TABLE subject_area_version (
  id varchar primary key,
  subject_area_id varchar not null,
  version bigint not null,
  version_status varchar not null,
  content_proto text not null,
  diagnostic_json text,
  source_version bigint,
  created_by varchar not null,
  created_at timestamp not null,
  activated_by varchar,
  activated_at timestamp,
  deprecated_by varchar,
  deprecated_at timestamp,
  archived_by varchar,
  archived_at timestamp,
  unique(subject_area_id, version),
  active_identity varchar
    GENERATED ALWAYS AS (CASE WHEN version_status = 'ACTIVE' THEN subject_area_id ELSE NULL END)
);

CREATE TABLE release_object_ref (
  id varchar primary key,
  release_id varchar not null,
  release_version varchar not null,
  object_type varchar not null,
  object_id varchar not null,
  object_version bigint not null,
  object_code varchar not null,
  object_display_name varchar not null,
  ref_role varchar not null,
  snapshot_proto text,
  created_at timestamp not null,
  unique(release_id, release_version, object_type, object_id, object_version, ref_role)
);

CREATE TABLE business_line (
  id varchar primary key,
  code varchar not null,
  created_by varchar not null,
  created_at timestamp not null,
  unique(code)
);

CREATE TABLE business_line_version (
  id varchar primary key,
  business_line_id varchar not null,
  version bigint not null,
  version_status varchar not null,
  content_proto text not null,
  diagnostic_json text,
  source_version bigint,
  created_by varchar not null,
  created_at timestamp not null,
  activated_by varchar,
  activated_at timestamp,
  deprecated_by varchar,
  deprecated_at timestamp,
  archived_by varchar,
  archived_at timestamp,
  unique(business_line_id, version),
  active_identity varchar
    GENERATED ALWAYS AS (CASE WHEN version_status = 'ACTIVE' THEN business_line_id ELSE NULL END)
);

CREATE TABLE dimension_event_binding_version (
  id varchar primary key,
  binding_id varchar not null,
  version bigint not null,
  version_status varchar not null,
  content_proto text not null,
  diagnostic_json text,
  source_version bigint,
  created_by varchar not null,
  created_at timestamp not null,
  activated_by varchar,
  activated_at timestamp,
  deprecated_by varchar,
  deprecated_at timestamp,
  archived_by varchar,
  archived_at timestamp,
  unique(binding_id, version),
  active_identity varchar
    GENERATED ALWAYS AS (CASE WHEN version_status = 'ACTIVE' THEN binding_id ELSE NULL END)
);

CREATE TABLE metric_expression_dependency (
  id varchar primary key,
  metric_id varchar not null,
  metric_version bigint not null,
  dependency_scope varchar not null,
  dependency_type varchar not null,
  dependency_id varchar,
  dependency_version bigint,
  referenced_symbol varchar,
  binding_status varchar not null,
  diagnostic_code varchar,
  created_at timestamp not null,
  unique(metric_id, metric_version, dependency_scope, dependency_type, dependency_id, dependency_version, referenced_symbol)
);

CREATE TABLE subject_area_metric_ref (
  id varchar not null,
  instance_id varchar not null unique,
  subject_area_id varchar not null,
  subject_area_version bigint not null,
  code varchar not null,
  display_name varchar not null,
  business_meaning_proto text not null,
  metric_id varchar not null,
  metric_version bigint not null,
  status varchar not null,
  selected_by varchar not null,
  selected_at timestamp not null,
  primary key(id, subject_area_version),
  unique(subject_area_id, subject_area_version, code)
);

CREATE TABLE subject_area_analysis_dimension_ref (
  id varchar primary key,
  subject_area_id varchar not null,
  subject_area_version bigint not null,
  analysis_dimension_id varchar not null,
  analysis_dimension_version bigint not null,
  status varchar not null,
  selected_by varchar not null,
  selected_at timestamp not null,
  unique(subject_area_id, subject_area_version, analysis_dimension_id)
);

CREATE TABLE subject_area_scenario_ref (
  id varchar primary key,
  subject_area_id varchar not null,
  subject_area_version bigint not null,
  scenario_id varchar not null,
  scenario_version bigint not null,
  status varchar not null,
  selected_by varchar not null,
  selected_at timestamp not null,
  unique(subject_area_id, subject_area_version, scenario_id)
);

CREATE TABLE analysis_dimension_level (
  id varchar primary key,
  analysis_dimension_id varchar not null,
  analysis_dimension_version bigint not null,
  level_code varchar not null,
  display_name varchar not null,
  parent_level_code varchar,
  sort_order int not null,
  status varchar not null,
  unique(analysis_dimension_id, analysis_dimension_version, level_code)
);

CREATE TABLE analysis_dimension_value_provider (
  id varchar primary key,
  analysis_dimension_id varchar not null,
  analysis_dimension_version bigint not null,
  level_code varchar not null,
  provider_type varchar not null,
  source_table_id varchar,
  source_table_version bigint,
  source_logic_unit_id varchar,
  source_logic_unit_version bigint,
  refresh_policy varchar not null,
  status varchar not null,
  created_by varchar not null,
  created_at timestamp not null,
  unique(analysis_dimension_id, analysis_dimension_version, level_code)
);

CREATE TABLE analysis_dimension_event_mapping (
  id varchar primary key,
  analysis_dimension_id varchar not null,
  analysis_dimension_version bigint not null,
  level_code varchar not null,
  event_id varchar not null,
  event_version bigint not null,
  dimension_id varchar not null,
  dimension_version bigint not null,
  binding_id varchar not null,
  binding_version bigint not null,
  alignment_status varchar not null,
  alignment_id varchar,
  created_at timestamp not null,
  unique(analysis_dimension_id, analysis_dimension_version, level_code, event_id, event_version)
);

CREATE TABLE analysis_dimension_value_alignment (
  id varchar primary key,
  analysis_dimension_id varchar not null,
  analysis_dimension_version bigint not null,
  level_code varchar not null,
  alignment_type varchar not null,
  evidence_json text,
  confirmed_by varchar,
  confirmed_at timestamp,
  diagnostic_status varchar not null,
  created_at timestamp not null,
  unique(analysis_dimension_id, analysis_dimension_version, level_code, alignment_type)
);

CREATE TABLE member_definition (
  id varchar primary key
);

CREATE TABLE member_definition_version (
  id varchar primary key,
  member_definition_id varchar not null references member_definition(id),
  version bigint not null,
  version_status varchar not null check (version_status IN ('DRAFT','ACTIVE','DEPRECATED','ARCHIVED')),
  active_identity varchar GENERATED ALWAYS AS
    (CASE WHEN version_status='ACTIVE' THEN member_definition_id ELSE NULL END),
  content_proto text not null,
  source_version bigint,
  created_by varchar not null,
  created_at timestamp not null,
  activated_by varchar,
  activated_at timestamp,
  deprecated_by varchar,
  deprecated_at timestamp,
  archived_by varchar,
  archived_at timestamp,
  unique(member_definition_id, version)
);

CREATE UNIQUE INDEX ux_member_definition_version_active
  ON member_definition_version(active_identity);

CREATE TABLE subject_area_member_source (
  id varchar not null,
  subject_area_id varchar not null,
  subject_area_version bigint not null,
  code varchar not null,
  display_name varchar not null,
  definition_origin_proto text not null,
  definition_proto text,
  bindings_proto text not null,
  fixed_arguments_proto text not null,
  primary key(id, subject_area_version),
  unique(subject_area_id, subject_area_version, code),
  foreign key(subject_area_id, subject_area_version) references subject_area_version(subject_area_id, version)
);

CREATE TABLE subject_area_member_output (
  source_id varchar not null,
  source_version bigint not null,
  slot varchar not null,
  output_kind varchar not null,
  binding_id varchar,
  binding_version bigint,
  logic_unit_id varchar,
  logic_unit_version bigint,
  dimension_slot varchar,
  symbol varchar,
  primary key(source_id, source_version, slot),
  foreign key(source_id, source_version) references subject_area_member_source(id, subject_area_version),
  foreign key(binding_id, binding_version) references dimension_event_binding_version(binding_id, version),
  foreign key(logic_unit_id, logic_unit_version) references logic_unit_version(logic_unit_id, version),
  check ((output_kind='DIMENSION' and binding_id is not null and binding_version is not null and logic_unit_id is null and logic_unit_version is null and dimension_slot is null and symbol is null)
      or (output_kind IN ('PROPERTY','CONTAINER_PROPERTY') and logic_unit_id is not null and logic_unit_version is not null and binding_id is null and binding_version is null and dimension_slot is null and symbol is null)
      or (output_kind='COMPUTED_DIMENSION' and dimension_slot is not null and symbol is not null and binding_id is null and binding_version is null and logic_unit_id is null and logic_unit_version is null)
      or (output_kind IN ('COMPUTED_PROPERTY','COMPUTED_CONTAINER_PROPERTY') and dimension_slot is null and symbol is not null and binding_id is null and binding_version is null and logic_unit_id is null and logic_unit_version is null))
);

CREATE TABLE subject_area_metric_measurement_binding (
  adoption_id varchar not null,
  adoption_version bigint not null,
  slot varchar not null,
  target_instance_id varchar not null,
  primary key(adoption_id, adoption_version, slot),
  foreign key(adoption_id, adoption_version) references subject_area_metric_ref(id, subject_area_version),
  foreign key(target_instance_id) references subject_area_metric_ref(instance_id)
);

CREATE TABLE subject_area_metric_fixed_argument (
  adoption_id varchar not null,
  adoption_version bigint not null,
  parameter_name varchar not null,
  value_proto text not null,
  primary key(adoption_id, adoption_version, parameter_name),
  foreign key(adoption_id, adoption_version) references subject_area_metric_ref(id, subject_area_version)
);

CREATE TABLE subject_area_metric_grouping_parameter (
  adoption_id varchar not null,
  adoption_version bigint not null,
  parameter_name varchar not null,
  primary key(adoption_id, adoption_version, parameter_name),
  foreign key(adoption_id, adoption_version) references subject_area_metric_ref(id, subject_area_version)
);

CREATE TABLE subject_area_metric_dimension_binding (
  adoption_id varchar not null,
  adoption_version bigint not null,
  slot varchar not null,
  binding_kind varchar not null,
  candidate_index int not null,
  analysis_dimension_id varchar not null,
  analysis_dimension_version bigint not null,
  level_code varchar not null,
  role varchar not null,
  primary key(adoption_id, adoption_version, slot, binding_kind, candidate_index),
  foreign key(adoption_id, adoption_version) references subject_area_metric_ref(id, subject_area_version),
  foreign key(analysis_dimension_id, analysis_dimension_version, level_code)
    references analysis_dimension_level(analysis_dimension_id, analysis_dimension_version, level_code),
  check ((binding_kind='INTERNAL' and candidate_index=0) or (binding_kind='GROUPING' and candidate_index>=0))
);

CREATE TABLE subject_area_metric_dimension_mapping (
  adoption_id varchar not null,
  adoption_version bigint not null,
  slot varchar not null,
  binding_kind varchar not null,
  candidate_index int not null,
  mapping_id varchar not null,
  primary key(adoption_id, adoption_version, slot, binding_kind, candidate_index, mapping_id),
  foreign key(adoption_id, adoption_version, slot, binding_kind, candidate_index)
    references subject_area_metric_dimension_binding(adoption_id, adoption_version, slot, binding_kind, candidate_index),
  foreign key(mapping_id) references analysis_dimension_event_mapping(id)
);

CREATE TABLE subject_area_metric_member_binding (
  adoption_id varchar not null,
  adoption_version bigint not null,
  slot varchar not null,
  binding_kind varchar not null,
  source_id varchar,
  source_version bigint,
  binding_proto text not null,
  primary key(adoption_id, adoption_version, slot),
  foreign key(adoption_id, adoption_version) references subject_area_metric_ref(id, subject_area_version),
  foreign key(source_id, source_version) references subject_area_member_source(id, subject_area_version),
  check ((binding_kind='SOURCE_CALL' and source_id is not null and source_version is not null)
      or (binding_kind in ('LOCAL','PARAMETER') and source_id is null and source_version is null))
);

CREATE TABLE subject_area_metric_relation_binding (
  adoption_id varchar not null,
  adoption_version bigint not null,
  slot varchar not null,
  binding_proto text not null,
  primary key(adoption_id, adoption_version, slot),
  foreign key(adoption_id, adoption_version) references subject_area_metric_ref(id, subject_area_version)
);

CREATE TABLE scenario_relation_filter_ref (
  id varchar primary key,
  scenario_id varchar not null,
  scenario_version bigint not null,
  code varchar not null,
  event_id varchar not null,
  event_version bigint not null,
  member_source_id varchar not null,
  member_source_version bigint not null,
  definition_proto text not null,
  unique(scenario_id, scenario_version, code),
  foreign key(scenario_id, scenario_version) references scenario_version(scenario_id, version),
  foreign key(event_id, event_version) references event_version(event_id, version),
  foreign key(member_source_id, member_source_version) references subject_area_member_source(id, subject_area_version)
);

CREATE TABLE scenario_member_source_ref (
  scenario_id varchar not null,
  scenario_version bigint not null,
  source_id varchar not null,
  source_version bigint not null,
  permission_proto text not null,
  primary key(scenario_id, scenario_version, source_id),
  foreign key(scenario_id, scenario_version) references scenario_version(scenario_id, version),
  foreign key(source_id, source_version) references subject_area_member_source(id, subject_area_version)
);

CREATE TABLE scenario_metric_ref (
  id varchar primary key,
  scenario_id varchar not null,
  scenario_version bigint not null,
  adoption_id varchar not null,
  adoption_version bigint not null,
  required_by_default boolean not null,
  sort_order int not null,
  created_at timestamp not null,
  unique(scenario_id, scenario_version, adoption_id, adoption_version)
);

CREATE TABLE scenario_analysis_dimension_ref (
  id varchar primary key,
  scenario_id varchar not null,
  scenario_version bigint not null,
  analysis_dimension_id varchar not null,
  analysis_dimension_version bigint not null,
  default_level_code varchar,
  required_by_default boolean not null,
  sort_order int not null,
  created_at timestamp not null,
  unique(scenario_id, scenario_version, analysis_dimension_id)
);

CREATE TABLE scenario_filter_ref (
  id varchar primary key,
  scenario_id varchar not null,
  scenario_version bigint not null,
  filter_target_type varchar not null check (filter_target_type = 'ANALYSIS_DIMENSION'),
  target_id varchar not null,
  target_version bigint not null,
  operators_proto text not null,
  value_provider_id varchar,
  required_by_default boolean not null,
  sort_order int not null,
  created_at timestamp not null,
  unique(scenario_id, scenario_version, filter_target_type, target_id, target_version)
);

CREATE TABLE scenario_pipeline_rule (
  id varchar primary key,
  scenario_id varchar not null,
  scenario_version bigint not null,
  rule_type varchar not null,
  display_name varchar not null,
  authorization_proto text not null,
  status varchar not null,
  sort_order int not null,
  created_at timestamp not null,
  unique(scenario_id, scenario_version, rule_type, sort_order)
);

CREATE TABLE subject_area_release (
  id varchar primary key,
  release_id varchar not null,
  release_display_name varchar not null,
  subject_area_id varchar not null,
  release_version varchar not null,
  subject_area_version bigint not null,
  engine_load_status varchar not null,
  created_by varchar not null,
  created_at timestamp not null,
  unique(release_id, release_version)
);

CREATE TABLE release_executable_catalog (
  release_id varchar not null,
  release_version varchar not null,
  scenario_id varchar not null,
  scenario_version bigint not null,
  producer_catalog_proto text not null,
  producer_catalog_hash varchar not null,
  executable_catalog_proto text not null,
  catalog_hash varchar not null,
  compiler_version varchar not null,
  created_at timestamp not null,
  primary key (release_id, release_version, scenario_id, scenario_version),
  foreign key (release_id, release_version)
    references subject_area_release(release_id, release_version)
);

CREATE TABLE release_artifact (
  release_id varchar not null,
  release_version varchar not null,
  subject_area_id varchar not null,
  subject_area_version bigint not null,
  compiler_version varchar not null,
  rule_set_version varchar not null,
  governance_closure_hash varchar not null,
  catalog_hash varchar not null,
  artifact_hash varchar not null,
  manifest_proto text not null,
  created_by varchar not null,
  created_at timestamp not null,
  primary key (release_id, release_version),
  unique (artifact_hash),
  foreign key (release_id, release_version)
    references subject_area_release(release_id, release_version)
);

CREATE TABLE release_query_capability (
  release_id varchar not null,
  release_version varchar not null,
  scenario_id varchar not null,
  scenario_version bigint not null,
  capability_proto text not null,
  capability_hash varchar not null,
  rule_set_version varchar not null,
  created_at timestamp not null,
  primary key (release_id, release_version, scenario_id, scenario_version),
  foreign key (release_id, release_version)
    references subject_area_release(release_id, release_version)
);

CREATE TABLE release_metric_dimension_capability (
  id varchar primary key,
  release_id varchar not null,
  release_version varchar not null,
  subject_area_id varchar not null,
  scenario_id varchar,
  scenario_version bigint,
  adoption_id varchar not null,
  adoption_version bigint not null,
  metric_id varchar not null,
  metric_version bigint not null,
  metric_event_id varchar not null,
  metric_event_version bigint not null,
  analysis_dimension_id varchar not null,
  analysis_dimension_version bigint not null,
  level_code varchar,
  available boolean not null,
  unavailable_code varchar,
  unavailable_reason text,
  producer_stage varchar,
  target_ref text,
  target_version_ref text,
  blocking boolean not null,
  page_blocking boolean not null,
  executable_blocking boolean not null,
  created_at timestamp not null
);

CREATE TABLE release_time_anchor_rule (
  id varchar primary key,
  release_id varchar not null,
  release_version varchar not null,
  subject_area_id varchar not null,
  scenario_id varchar,
  scenario_version bigint,
  event_id varchar not null,
  event_version bigint not null,
  topology_id varchar not null,
  topology_version bigint not null,
  topology_implementation_namespace_code varchar not null,
  topology_implementation_namespace_display_name varchar not null,
  effective_time_logic_unit_id varchar not null,
  effective_time_logic_unit_version bigint not null,
  effective_time_logic_unit_display_name varchar not null,
  time_role varchar not null,
  analysis_dimension_id varchar,
  analysis_dimension_version bigint,
  level_code varchar,
  partition_logic_unit_id varchar,
  partition_logic_unit_version bigint,
  diagnostic_json text,
  created_at timestamp not null,
  unique(release_id, release_version, event_id, event_version, analysis_dimension_id, analysis_dimension_version, level_code)
);

CREATE TABLE release_dimension_mapping (
  id varchar primary key,
  mapping_id varchar not null,
  release_id varchar not null,
  release_version varchar not null,
  subject_area_id varchar not null,
  event_id varchar not null,
  event_version bigint not null,
  analysis_dimension_id varchar not null,
  analysis_dimension_version bigint not null,
  level_code varchar not null,
  level_display_name varchar not null,
  dimension_id varchar not null,
  dimension_version bigint not null,
  dimension_code_namespace varchar not null,
  dimension_code_namespace_display_name varchar not null,
  dimension_display_name varchar not null,
  binding_id varchar not null,
  binding_version bigint not null,
  value_alignment_key varchar,
  value_alignment_status varchar not null,
  topology_id varchar not null,
  topology_version bigint not null,
  topology_implementation_namespace_code varchar not null,
  topology_implementation_namespace_display_name varchar not null,
  logic_unit_id varchar not null,
  logic_unit_version bigint not null,
  logic_unit_display_name varchar not null,
  join_path_refs_proto text not null,
  expression_proto text not null,
  created_at timestamp not null,
  unique(release_id,release_version,analysis_dimension_id,analysis_dimension_version,level_code,event_id,event_version)
);

CREATE TABLE release_unavailable_reason (
  id varchar primary key,
  release_id varchar not null,
  release_version varchar not null,
  scope_type varchar not null,
  subject_area_id varchar not null,
  scenario_id varchar,
  scenario_version bigint,
  metric_id varchar,
  metric_version bigint,
  analysis_dimension_id varchar,
  analysis_dimension_version bigint,
  level_code varchar,
  code varchar not null,
  reason text not null,
  producer_stage varchar,
  target_ref text,
  target_version_ref text,
  blocking boolean not null,
  page_blocking boolean not null,
  executable_blocking boolean not null,
  created_at timestamp not null
);

CREATE TABLE diagnostic_run (
  id varchar primary key,
  target_object_type varchar not null,
  target_object_id varchar not null,
  target_object_version bigint,
  stage varchar not null,
  status varchar not null,
  blocker_count int not null,
  warning_count int not null,
  info_count int not null,
  pass_count int not null,
  requested_by varchar not null,
  requested_at timestamp not null,
  finished_at timestamp
);

CREATE TABLE diagnostic_issue (
  id varchar primary key,
  diagnostic_run_id varchar not null,
  code varchar not null,
  severity varchar not null,
  message text not null,
  target text,
  target_version_ref text,
  source varchar,
  stage varchar not null,
  diagnostic_type varchar not null,
  blocker boolean not null,
  page_blocking boolean not null,
  executable_blocking boolean not null,
  related_objects_json text,
  action_json text,
  created_at timestamp not null
);

CREATE TABLE object_lifecycle_event (
  id varchar primary key,
  object_type varchar not null,
  object_id varchar not null,
  object_version bigint,
  event_type varchar not null,
  reason text,
  diagnostic_run_id varchar,
  created_by varchar not null,
  created_at timestamp not null
);

CREATE TABLE archive_request (
  id varchar primary key,
  object_type varchar not null,
  object_id varchar not null,
  requested_by varchar not null,
  requested_at timestamp not null,
  reason text,
  diagnostic_run_id varchar not null,
  decision varchar not null,
  decided_by varchar,
  decided_at timestamp
);

CREATE TABLE version_diff_cache (
  id varchar primary key,
  object_type varchar not null,
  object_id varchar not null,
  from_version bigint not null,
  to_version bigint not null,
  diff_json text not null,
  created_at timestamp not null,
  unique(object_type, object_id, from_version, to_version)
);

CREATE TABLE operation_audit (
  id varchar primary key,
  actor varchar not null,
  operation varchar not null,
  target_object_type varchar not null,
  target_object_id varchar not null,
  target_object_version bigint,
  before_summary_json text,
  after_summary_json text,
  diagnostic_run_id varchar,
  created_at timestamp not null
);

ALTER TABLE physical_table_version
  ADD CONSTRAINT ck_physical_table_version_positive CHECK (version > 0);
ALTER TABLE physical_column_version
  ADD CONSTRAINT ck_physical_column_version_positive CHECK (version > 0);
ALTER TABLE logical_table_version
  ADD CONSTRAINT ck_logical_table_version_positive CHECK (version > 0);
ALTER TABLE logic_unit_version
  ADD CONSTRAINT ck_logic_unit_version_positive CHECK (version > 0);
ALTER TABLE topology_version
  ADD CONSTRAINT ck_topology_version_positive CHECK (version > 0);
ALTER TABLE event_version
  ADD CONSTRAINT ck_event_version_positive CHECK (version > 0);

ALTER TABLE physical_column
  ADD CONSTRAINT fk_physical_column_table
  FOREIGN KEY (physical_table_id) REFERENCES physical_table(id);
ALTER TABLE physical_table_version
  ADD CONSTRAINT fk_physical_table_version_identity
  FOREIGN KEY (physical_table_id) REFERENCES physical_table(id);
ALTER TABLE physical_column_version
  ADD CONSTRAINT fk_physical_column_version_identity
  FOREIGN KEY (physical_column_id) REFERENCES physical_column(id);
ALTER TABLE logical_table_version
  ADD CONSTRAINT fk_logical_table_version_identity
  FOREIGN KEY (logical_table_id) REFERENCES logical_table(id);
ALTER TABLE logic_unit
  ADD CONSTRAINT fk_logic_unit_logical_table
  FOREIGN KEY (logical_table_id) REFERENCES logical_table(id);
ALTER TABLE logic_unit_version
  ADD CONSTRAINT fk_logic_unit_version_identity
  FOREIGN KEY (logic_unit_id) REFERENCES logic_unit(id);

ALTER TABLE physical_logical_table_ref
  ADD CONSTRAINT fk_physical_logical_ref_physical_version
  FOREIGN KEY (physical_table_id, physical_table_version)
  REFERENCES physical_table_version(physical_table_id, version);
ALTER TABLE physical_logical_table_ref
  ADD CONSTRAINT fk_physical_logical_ref_logical_version
  FOREIGN KEY (logical_table_id, logical_table_version)
  REFERENCES logical_table_version(logical_table_id, version);
ALTER TABLE logic_unit_source_ref
  ADD CONSTRAINT fk_logic_unit_source_ref_unit_version
  FOREIGN KEY (logic_unit_id, logic_unit_version)
  REFERENCES logic_unit_version(logic_unit_id, version);
ALTER TABLE logic_unit_source_ref
  ADD CONSTRAINT fk_logic_unit_source_ref_column_version
  FOREIGN KEY (physical_column_id, physical_column_version)
  REFERENCES physical_column_version(physical_column_id, version);

ALTER TABLE topology_version
  ADD CONSTRAINT fk_topology_version_identity
  FOREIGN KEY (topology_id) REFERENCES topology(id);
ALTER TABLE topology_logical_table_ref
  ADD CONSTRAINT ck_topology_logical_table_owner_version CHECK (topology_version > 0);
ALTER TABLE topology_logical_table_ref
  ADD CONSTRAINT ck_topology_logical_table_target_version CHECK (logical_table_version > 0);
ALTER TABLE topology_logical_table_ref
  ADD CONSTRAINT fk_topology_logical_table_owner_version
  FOREIGN KEY (topology_id, topology_version)
  REFERENCES topology_version(topology_id, version);
ALTER TABLE topology_logical_table_ref
  ADD CONSTRAINT fk_topology_logical_table_target_version
  FOREIGN KEY (logical_table_id, logical_table_version)
  REFERENCES logical_table_version(logical_table_id, version);

ALTER TABLE topology_join_path_ref
  ADD CONSTRAINT ck_topology_join_path_owner_version CHECK (topology_version > 0);
ALTER TABLE topology_join_path_ref
  ADD CONSTRAINT ck_topology_join_path_target_version CHECK (join_path_version > 0);
ALTER TABLE topology_join_path_ref
  ADD CONSTRAINT fk_topology_join_path_owner_version
  FOREIGN KEY (topology_id, topology_version)
  REFERENCES topology_version(topology_id, version);
ALTER TABLE topology_join_path_ref
  ADD CONSTRAINT fk_topology_join_path_target_version
  FOREIGN KEY (join_path_id, join_path_version)
  REFERENCES join_path_version(join_path_id, version);

ALTER TABLE join_path
  ADD CONSTRAINT fk_join_path_topology_identity
  FOREIGN KEY (topology_id) REFERENCES topology(id);

ALTER TABLE event
  ADD CONSTRAINT fk_event_topology_identity
  FOREIGN KEY (topology_id) REFERENCES topology(id);
ALTER TABLE event_version
  ADD CONSTRAINT fk_event_version_identity
  FOREIGN KEY (event_id) REFERENCES event(id);
ALTER TABLE event_logic_unit_ref
  ADD CONSTRAINT ck_event_logic_unit_owner_version CHECK (event_version > 0);
ALTER TABLE event_logic_unit_ref
  ADD CONSTRAINT ck_event_logic_unit_target_version CHECK (logic_unit_version > 0);
ALTER TABLE event_logic_unit_ref
  ADD CONSTRAINT fk_event_logic_unit_owner_version
  FOREIGN KEY (event_id, event_version)
  REFERENCES event_version(event_id, version);
ALTER TABLE event_logic_unit_ref
  ADD CONSTRAINT fk_event_logic_unit_target_version
  FOREIGN KEY (logic_unit_id, logic_unit_version)
  REFERENCES logic_unit_version(logic_unit_id, version);
ALTER TABLE event_slice_filter
  ADD CONSTRAINT ck_event_slice_filter_owner_version CHECK (event_version > 0);
ALTER TABLE event_slice_filter
  ADD CONSTRAINT fk_event_slice_filter_owner_version
  FOREIGN KEY (event_id, event_version)
  REFERENCES event_version(event_id, version);

ALTER TABLE subject_area_release
  ADD CONSTRAINT fk_subject_area_release_subject_version
  FOREIGN KEY (subject_area_id, subject_area_version)
  REFERENCES subject_area_version(subject_area_id, version);
ALTER TABLE release_object_ref
  ADD CONSTRAINT fk_release_object_ref_release
  FOREIGN KEY (release_id, release_version)
  REFERENCES subject_area_release(release_id, release_version);
ALTER TABLE release_executable_catalog
  ADD CONSTRAINT fk_release_catalog_scenario_version
  FOREIGN KEY (scenario_id, scenario_version)
  REFERENCES scenario_version(scenario_id, version);
ALTER TABLE release_artifact
  ADD CONSTRAINT fk_release_artifact_subject_version
  FOREIGN KEY (subject_area_id, subject_area_version)
  REFERENCES subject_area_version(subject_area_id, version);
ALTER TABLE release_query_capability
  ADD CONSTRAINT fk_release_query_capability_catalog
  FOREIGN KEY (release_id, release_version, scenario_id, scenario_version)
  REFERENCES release_executable_catalog(release_id, release_version, scenario_id, scenario_version);

ALTER TABLE release_metric_dimension_capability
  ADD CONSTRAINT fk_release_metric_dimension_capability_release
  FOREIGN KEY (release_id, release_version)
  REFERENCES subject_area_release(release_id, release_version);
ALTER TABLE release_metric_dimension_capability
  ADD CONSTRAINT fk_release_metric_dimension_capability_scenario
  FOREIGN KEY (scenario_id, scenario_version)
  REFERENCES scenario_version(scenario_id, version);
ALTER TABLE release_metric_dimension_capability
  ADD CONSTRAINT fk_release_metric_dimension_capability_adoption
  FOREIGN KEY (adoption_id, adoption_version)
  REFERENCES subject_area_metric_ref(id, subject_area_version);
ALTER TABLE release_metric_dimension_capability
  ADD CONSTRAINT fk_release_metric_dimension_capability_metric
  FOREIGN KEY (metric_id, metric_version)
  REFERENCES metric_version(metric_id, version);
ALTER TABLE release_metric_dimension_capability
  ADD CONSTRAINT fk_release_metric_dimension_capability_event
  FOREIGN KEY (metric_event_id, metric_event_version)
  REFERENCES event_version(event_id, version);
ALTER TABLE release_metric_dimension_capability
  ADD CONSTRAINT fk_release_metric_dimension_capability_analysis_dimension
  FOREIGN KEY (analysis_dimension_id, analysis_dimension_version)
  REFERENCES analysis_dimension_version(analysis_dimension_id, version);

ALTER TABLE release_time_anchor_rule
  ADD CONSTRAINT fk_release_time_anchor_rule_release
  FOREIGN KEY (release_id, release_version)
  REFERENCES subject_area_release(release_id, release_version);
ALTER TABLE release_time_anchor_rule
  ADD CONSTRAINT fk_release_time_anchor_rule_scenario
  FOREIGN KEY (scenario_id, scenario_version)
  REFERENCES scenario_version(scenario_id, version);
ALTER TABLE release_time_anchor_rule
  ADD CONSTRAINT fk_release_time_anchor_rule_event
  FOREIGN KEY (event_id, event_version)
  REFERENCES event_version(event_id, version);
ALTER TABLE release_time_anchor_rule
  ADD CONSTRAINT fk_release_time_anchor_rule_topology
  FOREIGN KEY (topology_id, topology_version)
  REFERENCES topology_version(topology_id, version);
ALTER TABLE release_time_anchor_rule
  ADD CONSTRAINT fk_release_time_anchor_rule_effective_time_logic_unit
  FOREIGN KEY (effective_time_logic_unit_id, effective_time_logic_unit_version)
  REFERENCES logic_unit_version(logic_unit_id, version);
ALTER TABLE release_time_anchor_rule
  ADD CONSTRAINT fk_release_time_anchor_rule_analysis_dimension
  FOREIGN KEY (analysis_dimension_id, analysis_dimension_version)
  REFERENCES analysis_dimension_version(analysis_dimension_id, version);
ALTER TABLE release_time_anchor_rule
  ADD CONSTRAINT fk_release_time_anchor_rule_partition_logic_unit
  FOREIGN KEY (partition_logic_unit_id, partition_logic_unit_version)
  REFERENCES logic_unit_version(logic_unit_id, version);

ALTER TABLE release_dimension_mapping
  ADD CONSTRAINT fk_release_dimension_mapping_release
  FOREIGN KEY (release_id, release_version)
  REFERENCES subject_area_release(release_id, release_version);
ALTER TABLE release_dimension_mapping
  ADD CONSTRAINT fk_release_dimension_mapping_event
  FOREIGN KEY (event_id, event_version)
  REFERENCES event_version(event_id, version);
ALTER TABLE release_dimension_mapping
  ADD CONSTRAINT fk_release_dimension_mapping_analysis_dimension
  FOREIGN KEY (analysis_dimension_id, analysis_dimension_version)
  REFERENCES analysis_dimension_version(analysis_dimension_id, version);
ALTER TABLE release_dimension_mapping
  ADD CONSTRAINT fk_release_dimension_mapping_dimension
  FOREIGN KEY (dimension_id, dimension_version)
  REFERENCES dimension_version(dimension_id, version);
ALTER TABLE release_dimension_mapping
  ADD CONSTRAINT fk_release_dimension_mapping_binding
  FOREIGN KEY (binding_id, binding_version)
  REFERENCES dimension_event_binding_version(binding_id, version);
ALTER TABLE release_dimension_mapping
  ADD CONSTRAINT fk_release_dimension_mapping_topology
  FOREIGN KEY (topology_id, topology_version)
  REFERENCES topology_version(topology_id, version);
ALTER TABLE release_dimension_mapping
  ADD CONSTRAINT fk_release_dimension_mapping_logic_unit
  FOREIGN KEY (logic_unit_id, logic_unit_version)
  REFERENCES logic_unit_version(logic_unit_id, version);

ALTER TABLE release_unavailable_reason
  ADD CONSTRAINT fk_release_unavailable_reason_release
  FOREIGN KEY (release_id, release_version)
  REFERENCES subject_area_release(release_id, release_version);
ALTER TABLE release_unavailable_reason
  ADD CONSTRAINT fk_release_unavailable_reason_scenario
  FOREIGN KEY (scenario_id, scenario_version)
  REFERENCES scenario_version(scenario_id, version);
ALTER TABLE release_unavailable_reason
  ADD CONSTRAINT fk_release_unavailable_reason_metric
  FOREIGN KEY (metric_id, metric_version)
  REFERENCES metric_version(metric_id, version);
ALTER TABLE release_unavailable_reason
  ADD CONSTRAINT fk_release_unavailable_reason_analysis_dimension
  FOREIGN KEY (analysis_dimension_id, analysis_dimension_version)
  REFERENCES analysis_dimension_version(analysis_dimension_id, version);

ALTER TABLE diagnostic_issue
  ADD CONSTRAINT fk_diagnostic_issue_run
  FOREIGN KEY (diagnostic_run_id) REFERENCES diagnostic_run(id);
ALTER TABLE object_lifecycle_event
  ADD CONSTRAINT fk_lifecycle_event_diagnostic_run
  FOREIGN KEY (diagnostic_run_id) REFERENCES diagnostic_run(id);
ALTER TABLE archive_request
  ADD CONSTRAINT fk_archive_request_diagnostic_run
  FOREIGN KEY (diagnostic_run_id) REFERENCES diagnostic_run(id);
ALTER TABLE operation_audit
  ADD CONSTRAINT fk_operation_audit_diagnostic_run
  FOREIGN KEY (diagnostic_run_id) REFERENCES diagnostic_run(id);

-- Governance version records are the only historical facts. Every version has
-- a closed lifecycle vocabulary and every typed relationship points at an exact
-- owner/target version rather than an identity row's current_version pointer.
ALTER TABLE join_path_version
  ADD CONSTRAINT ck_join_path_version_positive CHECK (version > 0);
ALTER TABLE metric_version
  ADD CONSTRAINT ck_metric_version_positive CHECK (version > 0);
ALTER TABLE dimension_version
  ADD CONSTRAINT ck_dimension_version_positive CHECK (version > 0);
ALTER TABLE dimension_event_binding_version
  ADD CONSTRAINT ck_dimension_event_binding_version_positive CHECK (version > 0);
ALTER TABLE analysis_dimension_version
  ADD CONSTRAINT ck_analysis_dimension_version_positive CHECK (version > 0);
ALTER TABLE scenario_version
  ADD CONSTRAINT ck_scenario_version_positive CHECK (version > 0);
ALTER TABLE subject_area_version
  ADD CONSTRAINT ck_subject_area_version_positive CHECK (version > 0);
ALTER TABLE business_line_version
  ADD CONSTRAINT ck_business_line_version_positive CHECK (version > 0);

ALTER TABLE physical_table_version
  ADD CONSTRAINT ck_physical_table_version_status
  CHECK (version_status IN ('DRAFT', 'ACTIVE', 'DEPRECATED', 'ARCHIVED'));
ALTER TABLE physical_column_version
  ADD CONSTRAINT ck_physical_column_version_status
  CHECK (version_status IN ('DRAFT', 'ACTIVE', 'DEPRECATED', 'ARCHIVED'));
ALTER TABLE logical_table_version
  ADD CONSTRAINT ck_logical_table_version_status
  CHECK (version_status IN ('DRAFT', 'ACTIVE', 'DEPRECATED', 'ARCHIVED'));
ALTER TABLE logic_unit_version
  ADD CONSTRAINT ck_logic_unit_version_status
  CHECK (version_status IN ('DRAFT', 'ACTIVE', 'DEPRECATED', 'ARCHIVED'));
ALTER TABLE topology_version
  ADD CONSTRAINT ck_topology_version_status
  CHECK (version_status IN ('DRAFT', 'ACTIVE', 'DEPRECATED', 'ARCHIVED'));
ALTER TABLE join_path_version
  ADD CONSTRAINT ck_join_path_version_status
  CHECK (version_status IN ('DRAFT', 'ACTIVE', 'DEPRECATED', 'ARCHIVED'));
ALTER TABLE event_version
  ADD CONSTRAINT ck_event_version_status
  CHECK (version_status IN ('DRAFT', 'ACTIVE', 'DEPRECATED', 'ARCHIVED'));
ALTER TABLE metric_version
  ADD CONSTRAINT ck_metric_version_status
  CHECK (version_status IN ('DRAFT', 'ACTIVE', 'DEPRECATED', 'ARCHIVED'));
ALTER TABLE dimension_version
  ADD CONSTRAINT ck_dimension_version_status
  CHECK (version_status IN ('DRAFT', 'ACTIVE', 'DEPRECATED', 'ARCHIVED'));
ALTER TABLE dimension_event_binding_version
  ADD CONSTRAINT ck_dimension_event_binding_version_status
  CHECK (version_status IN ('DRAFT', 'ACTIVE', 'DEPRECATED', 'ARCHIVED'));
ALTER TABLE business_line_version
  ADD CONSTRAINT ck_business_line_version_status
  CHECK (version_status IN ('DRAFT', 'ACTIVE', 'DEPRECATED', 'ARCHIVED'));
ALTER TABLE subject_area_version
  ADD CONSTRAINT ck_subject_area_version_status
  CHECK (version_status IN ('DRAFT', 'ACTIVE', 'DEPRECATED', 'ARCHIVED'));
ALTER TABLE analysis_dimension_version
  ADD CONSTRAINT ck_analysis_dimension_version_status
  CHECK (version_status IN ('DRAFT', 'ACTIVE', 'DEPRECATED', 'ARCHIVED'));
ALTER TABLE scenario_version
  ADD CONSTRAINT ck_scenario_version_status
  CHECK (version_status IN ('DRAFT', 'ACTIVE', 'DEPRECATED', 'ARCHIVED'));

-- An identity may have many historical versions, but the database must permit at most one ACTIVE version.
-- H2 unique indexes allow multiple NULL values, so non-ACTIVE rows do not participate in the constraint.

CREATE UNIQUE INDEX ux_physical_table_version_active
  ON physical_table_version(active_identity);

CREATE UNIQUE INDEX ux_physical_column_version_active
  ON physical_column_version(active_identity);

CREATE UNIQUE INDEX ux_logical_table_version_active
  ON logical_table_version(active_identity);

CREATE UNIQUE INDEX ux_logic_unit_version_active
  ON logic_unit_version(active_identity);

CREATE UNIQUE INDEX ux_topology_version_active
  ON topology_version(active_identity);

CREATE UNIQUE INDEX ux_join_path_version_active
  ON join_path_version(active_identity);

CREATE UNIQUE INDEX ux_event_version_active
  ON event_version(active_identity);

CREATE UNIQUE INDEX ux_metric_version_active
  ON metric_version(active_identity);

CREATE UNIQUE INDEX ux_dimension_version_active
  ON dimension_version(active_identity);

CREATE UNIQUE INDEX ux_dimension_event_binding_version_active
  ON dimension_event_binding_version(active_identity);

CREATE UNIQUE INDEX ux_business_line_version_active
  ON business_line_version(active_identity);

CREATE UNIQUE INDEX ux_subject_area_version_active
  ON subject_area_version(active_identity);

CREATE UNIQUE INDEX ux_analysis_dimension_version_active
  ON analysis_dimension_version(active_identity);

CREATE UNIQUE INDEX ux_scenario_version_active
  ON scenario_version(active_identity);

ALTER TABLE warehouse_discovery_snapshot
  ADD CONSTRAINT fk_warehouse_discovery_data_source
  FOREIGN KEY (data_source) REFERENCES warehouse_data_source(id);
ALTER TABLE warehouse_discovered_table
  ADD CONSTRAINT fk_warehouse_discovered_table_snapshot
  FOREIGN KEY (discovery_snapshot_id) REFERENCES warehouse_discovery_snapshot(id);
ALTER TABLE warehouse_discovered_table
  ADD CONSTRAINT fk_warehouse_discovered_table_registration
  FOREIGN KEY (registered_physical_table_id) REFERENCES physical_table(id);
ALTER TABLE warehouse_discovered_column
  ADD CONSTRAINT fk_warehouse_discovered_column_table
  FOREIGN KEY (discovered_table_id) REFERENCES warehouse_discovered_table(id);
ALTER TABLE physical_column_profile
  ADD CONSTRAINT fk_physical_column_profile_column_version
  FOREIGN KEY (physical_column_id, physical_column_version)
  REFERENCES physical_column_version(physical_column_id, version);

ALTER TABLE join_path_version
  ADD CONSTRAINT fk_join_path_version_identity
  FOREIGN KEY (join_path_id) REFERENCES join_path(id);
ALTER TABLE join_path_key_ref
  ADD CONSTRAINT fk_join_path_key_owner_version
  FOREIGN KEY (join_path_id, join_path_version)
  REFERENCES join_path_version(join_path_id, version);
ALTER TABLE join_path_key_ref
  ADD CONSTRAINT fk_join_path_key_source_unit_version
  FOREIGN KEY (source_logic_unit_id, source_logic_unit_version)
  REFERENCES logic_unit_version(logic_unit_id, version);
ALTER TABLE join_path_key_ref
  ADD CONSTRAINT fk_join_path_key_target_unit_version
  FOREIGN KEY (target_logic_unit_id, target_logic_unit_version)
  REFERENCES logic_unit_version(logic_unit_id, version);

ALTER TABLE metric_version
  ADD CONSTRAINT fk_metric_version_identity
  FOREIGN KEY (metric_id) REFERENCES metric(id);
ALTER TABLE metric_expression_dependency
  ADD CONSTRAINT fk_metric_expression_dependency_owner
  FOREIGN KEY (metric_id, metric_version)
  REFERENCES metric_version(metric_id, version);
ALTER TABLE dimension_version
  ADD CONSTRAINT fk_dimension_version_identity
  FOREIGN KEY (dimension_id) REFERENCES dimension(id);
ALTER TABLE dimension_event_binding
  ADD CONSTRAINT fk_dimension_binding_dimension_identity
  FOREIGN KEY (dimension_id) REFERENCES dimension(id);
ALTER TABLE dimension_event_binding_version
  ADD CONSTRAINT fk_dimension_binding_version_identity
  FOREIGN KEY (binding_id) REFERENCES dimension_event_binding(id);

ALTER TABLE business_line_version
  ADD CONSTRAINT fk_business_line_version_identity
  FOREIGN KEY (business_line_id) REFERENCES business_line(id);
ALTER TABLE subject_area
  ADD CONSTRAINT fk_subject_area_business_line_identity
  FOREIGN KEY (business_line_id) REFERENCES business_line(id);
ALTER TABLE subject_area_version
  ADD CONSTRAINT fk_subject_area_version_identity
  FOREIGN KEY (subject_area_id) REFERENCES subject_area(id);
ALTER TABLE analysis_dimension
  ADD CONSTRAINT fk_analysis_dimension_subject_identity
  FOREIGN KEY (subject_area_id) REFERENCES subject_area(id);
ALTER TABLE analysis_dimension_version
  ADD CONSTRAINT fk_analysis_dimension_version_identity
  FOREIGN KEY (analysis_dimension_id) REFERENCES analysis_dimension(id);
ALTER TABLE scenario
  ADD CONSTRAINT fk_scenario_subject_identity
  FOREIGN KEY (subject_area_id) REFERENCES subject_area(id);
ALTER TABLE scenario_version
  ADD CONSTRAINT fk_scenario_version_identity
  FOREIGN KEY (scenario_id) REFERENCES scenario(id);

ALTER TABLE subject_area_metric_ref
  ADD CONSTRAINT fk_subject_area_metric_owner
  FOREIGN KEY (subject_area_id, subject_area_version)
  REFERENCES subject_area_version(subject_area_id, version);
ALTER TABLE subject_area_metric_ref
  ADD CONSTRAINT fk_subject_area_metric_target
  FOREIGN KEY (metric_id, metric_version)
  REFERENCES metric_version(metric_id, version);
ALTER TABLE subject_area_analysis_dimension_ref
  ADD CONSTRAINT fk_subject_area_dimension_owner
  FOREIGN KEY (subject_area_id, subject_area_version)
  REFERENCES subject_area_version(subject_area_id, version);
ALTER TABLE subject_area_analysis_dimension_ref
  ADD CONSTRAINT fk_subject_area_dimension_target
  FOREIGN KEY (analysis_dimension_id, analysis_dimension_version)
  REFERENCES analysis_dimension_version(analysis_dimension_id, version);
ALTER TABLE subject_area_scenario_ref
  ADD CONSTRAINT fk_subject_area_scenario_owner
  FOREIGN KEY (subject_area_id, subject_area_version)
  REFERENCES subject_area_version(subject_area_id, version);
ALTER TABLE subject_area_scenario_ref
  ADD CONSTRAINT fk_subject_area_scenario_target
  FOREIGN KEY (scenario_id, scenario_version)
  REFERENCES scenario_version(scenario_id, version);

ALTER TABLE analysis_dimension_level
  ADD CONSTRAINT fk_analysis_dimension_level_owner
  FOREIGN KEY (analysis_dimension_id, analysis_dimension_version)
  REFERENCES analysis_dimension_version(analysis_dimension_id, version);
ALTER TABLE analysis_dimension_value_provider
  ADD CONSTRAINT fk_analysis_dimension_provider_owner
  FOREIGN KEY (analysis_dimension_id, analysis_dimension_version)
  REFERENCES analysis_dimension_version(analysis_dimension_id, version);
ALTER TABLE analysis_dimension_value_provider
  ADD CONSTRAINT fk_analysis_dimension_provider_table
  FOREIGN KEY (source_table_id, source_table_version)
  REFERENCES logical_table_version(logical_table_id, version);
ALTER TABLE analysis_dimension_value_provider
  ADD CONSTRAINT fk_analysis_dimension_provider_unit
  FOREIGN KEY (source_logic_unit_id, source_logic_unit_version)
  REFERENCES logic_unit_version(logic_unit_id, version);
ALTER TABLE analysis_dimension_event_mapping
  ADD CONSTRAINT fk_analysis_dimension_mapping_owner
  FOREIGN KEY (analysis_dimension_id, analysis_dimension_version)
  REFERENCES analysis_dimension_version(analysis_dimension_id, version);
ALTER TABLE analysis_dimension_event_mapping
  ADD CONSTRAINT fk_analysis_dimension_mapping_event
  FOREIGN KEY (event_id, event_version)
  REFERENCES event_version(event_id, version);
ALTER TABLE analysis_dimension_event_mapping
  ADD CONSTRAINT fk_analysis_dimension_mapping_dimension
  FOREIGN KEY (dimension_id, dimension_version)
  REFERENCES dimension_version(dimension_id, version);
ALTER TABLE analysis_dimension_event_mapping
  ADD CONSTRAINT fk_analysis_dimension_mapping_binding
  FOREIGN KEY (binding_id, binding_version)
  REFERENCES dimension_event_binding_version(binding_id, version);
ALTER TABLE analysis_dimension_value_alignment
  ADD CONSTRAINT fk_analysis_dimension_alignment_owner
  FOREIGN KEY (analysis_dimension_id, analysis_dimension_version)
  REFERENCES analysis_dimension_version(analysis_dimension_id, version);
ALTER TABLE analysis_dimension_event_mapping
  ADD CONSTRAINT ck_analysis_dimension_mapping_alignment_status
  CHECK (alignment_status IN ('PROVEN_SAME_KEY', 'EXPLICIT_MAPPING', 'MANUALLY_CONFIRMED',
                              'NOT_ALIGNABLE', 'SINGLE_EVENT_ONLY'));
ALTER TABLE release_dimension_mapping
  ADD CONSTRAINT ck_release_dimension_mapping_alignment_status
  CHECK (value_alignment_status IN ('PROVEN_SAME_KEY', 'EXPLICIT_MAPPING', 'MANUALLY_CONFIRMED',
                                    'NOT_ALIGNABLE', 'SINGLE_EVENT_ONLY'));

ALTER TABLE scenario_metric_ref
  ADD CONSTRAINT fk_scenario_metric_owner
  FOREIGN KEY (scenario_id, scenario_version)
  REFERENCES scenario_version(scenario_id, version);
ALTER TABLE scenario_metric_ref
  ADD CONSTRAINT fk_scenario_metric_target
  FOREIGN KEY (adoption_id, adoption_version)
  REFERENCES subject_area_metric_ref(id, subject_area_version);
ALTER TABLE scenario_analysis_dimension_ref
  ADD CONSTRAINT fk_scenario_dimension_owner
  FOREIGN KEY (scenario_id, scenario_version)
  REFERENCES scenario_version(scenario_id, version);
ALTER TABLE scenario_analysis_dimension_ref
  ADD CONSTRAINT fk_scenario_dimension_target
  FOREIGN KEY (analysis_dimension_id, analysis_dimension_version)
  REFERENCES analysis_dimension_version(analysis_dimension_id, version);
ALTER TABLE scenario_filter_ref
  ADD CONSTRAINT fk_scenario_filter_owner
  FOREIGN KEY (scenario_id, scenario_version)
  REFERENCES scenario_version(scenario_id, version);
ALTER TABLE scenario_filter_ref
  ADD CONSTRAINT fk_scenario_filter_target
  FOREIGN KEY (target_id, target_version)
  REFERENCES analysis_dimension_version(analysis_dimension_id, version);
ALTER TABLE scenario_filter_ref
  ADD CONSTRAINT fk_scenario_filter_provider
  FOREIGN KEY (value_provider_id) REFERENCES analysis_dimension_value_provider(id);
ALTER TABLE scenario_pipeline_rule
  ADD CONSTRAINT fk_scenario_pipeline_owner
  FOREIGN KEY (scenario_id, scenario_version)
  REFERENCES scenario_version(scenario_id, version);

CREATE INDEX idx_physical_column_table ON physical_column(physical_table_id);
CREATE INDEX idx_physical_logical_table_ref_physical ON physical_logical_table_ref(physical_table_id, physical_table_version);
CREATE INDEX idx_physical_logical_table_ref_logical ON physical_logical_table_ref(logical_table_id, logical_table_version);
CREATE INDEX idx_logic_unit_logical_table ON logic_unit(logical_table_id);
CREATE INDEX idx_topology_logical_table_ref_topology ON topology_logical_table_ref(topology_id, topology_version);
CREATE INDEX idx_join_path_topology ON join_path(topology_id);
CREATE INDEX idx_event_topology ON event(topology_id);
CREATE INDEX idx_analysis_dimension_subject_area ON analysis_dimension(subject_area_id);
CREATE INDEX idx_scenario_subject_area ON scenario(subject_area_id);
CREATE INDEX idx_subject_area_business_line ON subject_area(business_line_id);
CREATE INDEX idx_release_object_ref_release ON release_object_ref(release_id);

CREATE TABLE query_definition (
  id varchar PRIMARY KEY,
  current_version bigint NOT NULL CHECK (current_version > 0)
);
CREATE TABLE query_definition_version (
  definition_id varchar NOT NULL REFERENCES query_definition(id),
  version bigint NOT NULL CHECK (version > 0),
  name varchar NOT NULL,
  scenario_id varchar NOT NULL,
  scenario_version bigint NOT NULL,
  release_id varchar NOT NULL,
  release_version varchar NOT NULL,
  subject_area_id varchar NOT NULL,
  subject_area_version bigint NOT NULL,
  catalog_hash varchar NOT NULL,
  capability_hash varchar NOT NULL,
  intent_hash varchar NOT NULL,
  artifact_sha256 varchar NOT NULL,
  artifact_proto text NOT NULL,
  created_by varchar NOT NULL,
  created_at timestamp NOT NULL,
  PRIMARY KEY(definition_id,version)
);
CREATE INDEX idx_query_definition_publication ON query_definition_version(release_id,release_version,scenario_id,scenario_version);

CREATE TABLE query_run (
  run_id varchar PRIMARY KEY,
  subject varchar NOT NULL,
  scenario_id varchar NOT NULL,
  scenario_version bigint NOT NULL,
  release_id varchar NOT NULL,
  release_version varchar NOT NULL,
  subject_area_id varchar NOT NULL,
  subject_area_version bigint NOT NULL,
  catalog_hash varchar NOT NULL,
  capability_hash varchar NOT NULL,
  intent_hash varchar NOT NULL,
  execution_state varchar NOT NULL CHECK(execution_state IN ('ACCEPTED','RUNNING','SUCCEEDED','FAILED','CANCELLED','INTERRUPTED')),
  delivery_state varchar NOT NULL CHECK(delivery_state IN ('PENDING','DIRECT','SAVED','FAILED')),
  accepted_at timestamp NOT NULL,
  completed_at timestamp,
  history_enabled boolean NOT NULL,
  save_requested boolean NOT NULL,
  intent_proto text,
  definition_proto text,
  question text,
  execution_error text,
  execution_receipt_proto text,
  service_observation_proto text,
  delivery_error text
);
CREATE INDEX idx_query_run_scope ON query_run(subject,release_id,release_version,scenario_id,scenario_version,accepted_at);
CREATE TABLE query_result_manifest (
  run_id varchar PRIMARY KEY REFERENCES query_run(run_id),
  sha256 varchar NOT NULL,
  byte_count bigint NOT NULL CHECK(byte_count >= 0),
  row_count bigint NOT NULL CHECK(row_count >= 0),
  saved_at timestamp NOT NULL
);
CREATE TABLE query_usage_note (
  id varchar PRIMARY KEY,
  run_id varchar NOT NULL REFERENCES query_run(run_id),
  subject varchar NOT NULL,
  note_text text NOT NULL,
  created_at timestamp NOT NULL
);
CREATE INDEX idx_query_usage_note_run ON query_usage_note(run_id,created_at);
CREATE TABLE query_usage_reference (
  run_id varchar NOT NULL REFERENCES query_run(run_id),
  asset_type varchar NOT NULL,
  asset_id varchar NOT NULL,
  asset_version varchar NOT NULL,
  PRIMARY KEY(run_id,asset_type,asset_id,asset_version)
);
CREATE INDEX idx_query_usage_reference_asset ON query_usage_reference(asset_type,asset_id,asset_version);

CREATE TABLE platform_storage_format (
  singleton_id smallint PRIMARY KEY CHECK (singleton_id = 1),
  format_version integer NOT NULL CHECK (format_version > 0)
);

-- 完整结构初始化完成后才写入格式身份。
INSERT INTO platform_storage_format(singleton_id, format_version) VALUES (1, 19);
