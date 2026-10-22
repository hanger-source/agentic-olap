package scenario

import (
	"fmt"
	"math/big"
	"path/filepath"
	"strings"
)

func meaning(description string) object {
	return object{"definition": description, "unit": "数量合同确定", "grain": "定义声明的完整观察对象", "limitations": array{description}}
}
func bindings(dimensions, members, measurements object) object {
	if dimensions == nil {
		dimensions = object{}
	}
	if members == nil {
		members = object{}
	}
	values := object{}
	for name, value := range measurements {
		values[name] = obj(value)["instance"]
	}
	return object{"dimensions": dimensions, "members": members, "measurements": values, "groupingCandidates": object{}, "relations": object{}}
}
func period(start, end string) object {
	return object{"kind": "RANGE", "window": object{"valueType": "DATE", "boundary": "LEFT_CLOSED_RIGHT_OPEN", "start": start, "end": end}}
}
func coordinate(capability object) object {
	scenario := obj(capability["scenario"])
	return object{"scenarioId": scenario["scenarioId"], "scenarioVersion": scenario["scenarioVersion"], "releaseId": capability["releaseId"], "releaseVersion": capability["releaseVersion"]}
}
func intent(capability, root object, stages ...any) object {
	if stages == nil {
		stages = array{}
	}
	return merge(coordinate(capability), object{"formatVersion": "olap-query-intent/v6", "computation": object{"root": root, "inputs": object{}, "stages": stages}})
}
func logic(unit object) object {
	return object{"kind": "LOGIC_UNIT", "logicUnitId": unit["id"], "logicUnitVersion": unit["version"]}
}
func logicRef(unit object) object {
	return object{"logicUnitId": unit["id"], "logicUnitVersion": unit["version"]}
}
func (r *remote) logicalUnits(table object) object {
	result := object{}
	for _, value := range arr(r.read("read-logical-table-units", exactRef(table))) {
		ref := obj(value)
		unit := r.asset("LOGIC_UNIT", object{"id": ref["logicUnitId"], "version": ref["logicUnitVersion"]})
		result[str(unit["code"])] = unit
	}
	return result
}
func (r *remote) adoptTable(source object, name string, types map[string]string, key, timeColumn, domain string, orders bool) (object, object) {
	surveyed := r.write("discover-source", object{"dataSourceId": source["dataSourceId"], "sourceIdentityHash": source["sourceIdentityHash"], "catalog": nil, "schemaName": "main", "tablePattern": name, "tableTypes": array{"TABLE"}, "limit": 10, "temporalKeys": object{}, "snapshotUniqueKeys": object{}, "requestedBy": r.actor})
	require(surveyed["truncated"] == false && surveyed["status"] == "SUCCEEDED", "来源调查必须完整成功")
	tables := r.pages("read-source-discovery-tables", object{"snapshotId": surveyed["snapshotId"]})
	require(len(tables) == 1, "本例每次明确调查一张业务表")
	observed := obj(tables[0])
	columns := r.pages("read-source-discovery-columns", object{"discoveredTableId": observed["id"]})
	actual := map[string]bool{}
	physicalColumns := array{}
	for _, value := range columns {
		column := obj(value)
		columnName := str(column["columnName"])
		actual[columnName] = true
		jdbc := object{}
		for _, field := range []string{"jdbcType", "typeName", "precision", "scale", "nullable"} {
			jdbc[field] = column[field]
		}
		physicalColumns = append(physicalColumns, object{"columnName": columnName, "displayName": columnName, "columnType": column["typeName"], "sensitivityLevel": "PUBLIC", "nullable": column["nullable"], "description": "作者明确的原始字段", "jdbcColumnType": jdbc})
	}
	require(len(actual) == len(types), "实际源字段与作者声明不一致：%s", name)
	for column := range types {
		require(actual[column], "实际源缺少字段：%s.%s", name, column)
	}
	physical := r.write("create-physical-table", object{"code": name, "tableName": name, "displayName": name, "description": "保留真实源记录与NULL", "dataDomain": domain, "owner": r.actor, "sensitivityLevel": "PUBLIC", "sourceSystem": domain + "-example", "dataSource": source["dataSourceId"], "dialectId": "duckdb", "catalog": observed["catalogName"], "schemaName": observed["schemaName"], "schemaHash": observed["schemaHash"], "sourceConstraints": object{"discoveredTableId": observed["id"], "sourceCollectionIds": array{}}, "createdBy": r.actor, "columns": physicalColumns})
	r.activate("PHYSICAL_TABLE", physical)
	units := array{}
	for _, value := range r.pages("read-physical-columns", exactRef(physical)) {
		column := obj(value)
		columnName := str(column["columnName"])
		filterable := types[columnName] == "STRING"
		aggregatable := types[columnName] == "NUMBER"
		if orders {
			filterable = columnName == "customer"
			aggregatable = !strings.HasSuffix(columnName, "date")
		}
		units = append(units, object{"code": columnName, "displayName": columnName, "sourceKind": "COLUMN", "expressionJson": nil, "logicType": types[columnName], "isTime": columnName == timeColumn, "isPrimaryKey": columnName == key, "isForeignKey": false, "filterable": filterable, "aggregatable": aggregatable, "sourceRefs": array{object{"physicalColumnId": column["id"], "physicalColumnVersion": 1, "refRole": "DIRECT_SOURCE"}}})
	}
	logical := r.write("create-logical-table", object{"physicalTableId": physical["id"], "physicalTableVersion": physical["version"], "code": name, "displayName": name, "tableRole": "FACT", "description": "记录身份与观察对象分别声明", "owner": r.actor, "createdBy": r.actor, "logicUnits": units})
	r.activate("LOGICAL_TABLE", logical)
	return logical, r.logicalUnits(logical)
}
func (r *remote) calculation(code string, definition object) object {
	metric := r.write("create-metric-calculation", object{"code": code, "displayName": code, "owner": r.actor, "definition": definition, "description": "可组合的业务语义定义", "businessMeaning": meaning(code), "createdBy": r.actor})
	r.activate("METRIC", metric)
	return metric
}
func (r *remote) publish(subject, scenario object, releaseID, releaseVersion string) object {
	r.activate("SUBJECT_AREA", subject)
	r.write("build-release", object{"releaseId": releaseID, "releaseVersion": releaseVersion, "releaseDisplayName": releaseID + " " + releaseVersion, "subjectAreaId": subject["id"], "subjectAreaVersion": subject["version"], "createdBy": r.actor})
	return obj(r.call("read-publication", object{"scenarioId": scenario["id"], "scenarioVersion": scenario["version"], "releaseId": releaseID, "releaseVersion": releaseVersion}))
}
func numberText(value any) string {
	switch n := value.(type) {
	case string:
		return n
	case interface{ String() string }:
		return n.String()
	}
	panic(failure{fmt.Errorf("值不是精确数字：%T", value)})
}
func verifyScalar(label string, rows array, expected string) {
	verifyScalarWithin(label, rows, expected, big.NewRat(1, 100000000))
}
func verifyExact(label string, rows array, expected string) {
	verifyScalarWithin(label, rows, expected, new(big.Rat))
}
func verifyScalarWithin(label string, rows array, expected string, tolerance *big.Rat) {
	require(len(rows) == 1 && len(arr(rows[0])) == 1, "%s 应产生单行单值", label)
	cell := obj(arr(rows[0])[0])
	require(cell["type"] != "NULL", "%s 不应为NULL", label)
	actual, ok := new(big.Rat).SetString(numberText(cell["value"]))
	require(ok, "%s 结果不是十进制", label)
	want, ok := new(big.Rat).SetString(expected)
	require(ok, "独立预期不是精确十进制")
	delta := new(big.Rat).Sub(actual, want)
	delta.Abs(delta)
	require(delta.Cmp(tolerance) <= 0, "%s 实际 %v，独立预期 %s", label, cell["value"], expected)
}

type query struct {
	Label  string `json:"label"`
	Intent object `json:"intent"`
}

func (r *remote) executeQueries(publication object, queries []query, definitionID string, verify func(string, []string, array)) object {
	require(len(queries) > 0, "业务作者必须提交完整Query")
	capability := obj(publication["capability"])
	write(filepath.Join(r.output, "queries.json"), queries)
	command := merge(coordinate(capability), object{"definitionId": definitionID, "sourceVersion": nil, "name": queries[0].Label, "capabilityHash": capability["capabilityHash"], "intent": queries[0].Intent})
	saved := obj(r.call("save-query", command))
	reference := saved["queryReference"]
	if definitionID == "orders-reusable-net" {
		r.call("update-query", merge(command, object{"sourceVersion": 1, "name": "原单期净额说明修订"}))
		r.callStatus("update-query", merge(command, object{"sourceVersion": 1}), false)
		require(obj(r.call("read-query", object{"queryReference": reference}))["queryReference"] != nil, "精确旧查询引用消失")
	}
	observations := array{}
	for index, q := range queries {
		r.call("validate-query", object{"intent": q.Intent})
		receipt := obj(r.call("execute-query", object{"intent": q.Intent, "saveResult": index > 0, "question": q.Label}))
		require(receipt["status"] == "SUCCEEDED", "业务执行失败：%s %s", q.Label, encoded(receipt))
		var names []string
		var rows array
		if index > 0 {
			names, rows = r.rows(receipt["resultReference"])
		} else {
			result := directArtifact(obj(receipt["result"]))
			frame := obj(result["result"])
			for _, column := range arr(frame["columns"]) {
				names = append(names, str(obj(obj(column)["symbol"])["name"]))
			}
			rows = arr(frame["rows"])
		}
		verify(q.Label, names, rows)
		usage := obj(r.call("read-query-usage", object{"runId": receipt["runId"], "offset": 0, "limit": 20}))
		require(obj(usage["run"])["execution"] == "SUCCEEDED", "使用记录丢失执行结局")
		observations = append(observations, object{"label": q.Label, "receipt": receipt, "names": names, "rows": rows, "usage": usage})
		fmt.Println("远程真实业务值通过：" + q.Label)
	}
	receipt := obj(r.call("execute-query", object{"queryReference": reference, "saveResult": true, "question": "精确保存查询复用"}))
	require(receipt["status"] == "SUCCEEDED", "精确保存查询执行失败")
	names, rows := r.rows(receipt["resultReference"])
	verify(queries[0].Label, names, rows)
	r.call("append-query-usage-note", object{"runId": receipt["runId"], "text": "真实MCP、保存与重启读取"})
	savedState := object{"coordinate": coordinate(capability), "queryReference": reference, "resultReference": receipt["resultReference"], "runId": receipt["runId"], "label": queries[0].Label}
	write(filepath.Join(r.output, "observations.json"), observations)
	write(filepath.Join(r.output, "reopen.json"), savedState)
	return savedState
}
func (r *remote) reopen(saved object, verify func(string, []string, array)) {
	query := obj(r.call("read-query", object{"queryReference": saved["queryReference"]}))
	require(string(encoded(query["queryReference"])) == string(encoded(saved["queryReference"])), "重启改变精确保存查询身份")
	names, rows := r.rows(saved["resultReference"])
	verify(str(saved["label"]), names, rows)
	r.call("read-publication", saved["coordinate"])
	r.call("read-query-usage", object{"runId": saved["runId"], "offset": 0, "limit": 20})
}
