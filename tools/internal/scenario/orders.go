package scenario

import (
	"fmt"
	"path/filepath"
	"strings"
)

var orderTables = []string{"orders", "payments", "refunds", "fees"}
var orderDates = map[string]string{"orders": "ordered_date", "payments": "paid_date", "refunds": "refunded_date", "fees": "charged_date"}
var orderKeys = map[string]string{"orders": "order_id", "payments": "payment_id", "refunds": "refund_id", "fees": "fee_id"}

const customerDimension = "example.customer"
const dateDimension = "example.business_date"

type ordersModel struct{ definitions, bindings, line object }

func buildOrdersBase(r *remote, authored object) ordersModel {
	source := r.source("example-orders")
	tables, units, events, paths := object{}, object{}, object{}, object{}
	for _, table := range orderTables {
		types := map[string]string{orderKeys[table]: "STRING", orderDates[table]: "DATE"}
		if table == "orders" {
			types["customer"] = "STRING"
		} else {
			types["order_id"] = "STRING"
			types["amount"] = "NUMBER"
		}
		logical, logicalUnits := r.adoptTable(source, table, types, orderKeys[table], orderDates[table], "orders", true)
		tables[table] = logical
		units[table] = logicalUnits
	}
	for _, table := range orderTables {
		logical := obj(tables[table])
		tableUnits := obj(units[table])
		orderUnits := obj(units["orders"])
		topology := r.write("create-topology", object{"code": table, "displayName": table, "topologyImplementationNamespaceCode": "example.orders", "topologyImplementationNamespaceDisplayName": "订单事实实现空间", "backboneLogicalTableId": logical["id"], "backboneLogicalTableVersion": 1, "owner": r.actor, "description": "独立事实", "createdBy": r.actor})
		path := object{"joinPathRefs": array{}}
		if table != "orders" {
			r.write("create-topology-table-ref", object{"topologyId": topology["id"], "topologyVersion": 1, "logicalTableId": obj(tables["orders"])["id"], "logicalTableVersion": 1, "usageRole": "BRIDGE_NODE"})
			joined := r.write("create-join-path", object{"topologyId": topology["id"], "topologyVersion": 1, "code": "original_order", "sourceLogicalTableId": logical["id"], "sourceLogicalTableVersion": 1, "targetLogicalTableId": obj(tables["orders"])["id"], "targetLogicalTableVersion": 1, "joinType": "INNER", "cardinality": "MANY_TO_ONE", "temporalConfigJson": "{}", "nullPolicy": "SQL_EQUALS_NULL_NEVER_MATCHES", "createdBy": r.actor, "keyRefs": array{object{"sourceLogicUnitId": obj(tableUnits["order_id"])["id"], "sourceLogicUnitVersion": 1, "targetLogicUnitId": obj(orderUnits["order_id"])["id"], "targetLogicUnitVersion": 1}}})
			path = object{"joinPathRefs": array{object{"joinPathId": joined["id"], "joinPathVersion": 1}}}
		}
		r.activate("TOPOLOGY", topology)
		measures := array{logicRef(obj(tableUnits[orderKeys[table]]))}
		if table != "orders" {
			measures = append(measures, logicRef(obj(tableUnits["amount"])))
		}
		event := r.write("create-event", object{"topologyId": topology["id"], "topologyVersion": 1, "code": "registered", "displayName": table, "owner": r.actor, "eventTimeLogicUnitId": obj(tableUnits[orderDates[table]])["id"], "eventTimeLogicUnitVersion": 1, "identity": object{"recordKey": array{logicRef(obj(tableUnits[orderKeys[table]]))}, "entityRoles": object{"customer": array{logicRef(obj(orderUnits["customer"]))}, "order": array{logicRef(obj(orderUnits["order_id"]))}}}, "roles": object{"customer": path, "order": path}, "filterableLogicUnitRefs": array{logicRef(obj(orderUnits["customer"])), logicRef(obj(orderUnits["ordered_date"]))}, "measureLogicUnitRefs": measures, "description": "记录身份独立；客户与原单日期经多对一关系访问", "createdBy": r.actor})
		r.activate("EVENT", event)
		events[table] = event
		paths[table] = path
	}
	facts := object{}
	for _, entry := range []struct{ key, table, code, input, aggregation string }{{"gross", "payments", "gross", "amount", "SUM"}, {"refund", "refunds", "refund", "amount", "SUM"}, {"orders", "orders", "order_count", "order_id", "COUNT"}, {"fee", "fees", "service_fee", "amount", "SUM"}} {
		contract := authored["money"]
		unit := "CNY"
		if entry.key == "orders" {
			contract = authored["count"]
			unit = "单"
		}
		m := meaning(entry.code)
		m["unit"] = unit
		fact := r.write("create-metric", object{"sourceEventId": obj(events[entry.table])["id"], "sourceEventVersion": 1, "code": entry.code, "displayName": entry.code, "owner": r.actor, "inputExpressionJson": string(encoded(logic(obj(obj(units[entry.table])[entry.input])))), "aggregation": entry.aggregation, "distinct": false, "metricKind": "BASE", "mergePolicy": "ADDITIVE", "privatePredicateJson": nil, "roles": object{}, "description": "独立事实测量，不把支付和退款相乘", "businessMeaning": m, "snapshotRule": nil, "valueContract": contract, "aggregationRounding": "NONE", "createdBy": r.actor})
		r.activate("METRIC", fact)
		facts[entry.key] = fact
	}
	for _, code := range []string{customerDimension, dateDimension} {
		dimension := r.write("create-dimension", object{"code": code, "dimensionCodeNamespaceDisplayName": "订单公共语义", "displayName": code, "owner": r.actor, "description": "客户身份/业务日期", "createdBy": r.actor})
		require(dimension["id"] == code, "公共维度身份必须与作者一致")
		r.activate("DIMENSION", dimension)
	}
	global := object{}
	for _, table := range orderTables {
		bound := object{}
		for _, kind := range []string{"customer", "original", "occurrence"} {
			if table == "orders" && kind == "occurrence" {
				bound[kind] = bound["original"]
				continue
			}
			dimension := dateDimension
			unit := obj(obj(units["orders"])["ordered_date"])
			path := obj(paths[table])
			if kind == "customer" {
				dimension = customerDimension
				unit = obj(obj(units["orders"])["customer"])
			} else if kind == "occurrence" {
				unit = obj(obj(units[table])[orderDates[table]])
				path = object{"joinPathRefs": array{}}
			}
			binding := r.write("create-dimension-event-binding", object{"dimensionId": dimension, "dimensionVersion": 1, "eventId": obj(events[table])["id"], "eventVersion": 1, "logicUnitId": unit["id"], "logicUnitVersion": 1, "expressionJson": nil, "accessPath": path, "roles": object{}, "createdBy": r.actor})
			r.activate("DIMENSION_EVENT_BINDING", binding)
			bound[kind] = r.asset("DIMENSION_EVENT_BINDING", binding)
		}
		global[table] = bound
	}
	definitions := object{}
	for key, value := range obj(authored["definitions"]) {
		if key == "net_v2" {
			continue
		}
		body := obj(clone(value))
		code := key
		if fact, fixed := facts[key]; fixed {
			target := obj(obj(arr(obj(obj(obj(body["calculation"])["computation"])["root"])["calls"])[0])["target"])
			require(target["kind"] == "FIXED" && obj(target["metric"])["id"] == "authored.fact", "事实引用位置发生变化")
			target["metric"] = object{"id": obj(fact)["id"], "version": "1"}
			code = key + "_by_customer"
		} else if key == "growth" {
			code = "cohort_growth"
		}
		definitions[key] = r.calculation(code, body)
	}
	line := r.write("create-business-line", object{"code": "example", "displayName": "自含订单业务", "owner": r.actor, "description": "人工确定性业务输入", "createdBy": r.actor})
	r.activate("BUSINESS_LINE", line)
	return ordersModel{definitions, global, line}
}

func orderContext(r *remote, subject object, model ordersModel, memberDefinition object, netVersion int, full bool, releaseVersion string, sourceVersion int) object {
	version := subject["version"]
	analyses, mappings := object{}, object{}
	for _, kind := range []string{"customer", "original", "occurrence"} {
		level := "day"
		dimension := dateDimension
		valueType := "DATE"
		basis := "同一公历日期值域，保持日期原值"
		if kind == "customer" {
			level = "customer"
			dimension = customerDimension
			valueType = "TEXT"
			basis = "各事实经已登记关系使用同一客户字段"
		}
		payload := object{"subjectAreaId": subject["id"], "subjectAreaVersion": version, "code": kind, "displayName": kind, "description": "精确客户/时间角色", "levelCode": level, "levelDisplayName": level, "createdBy": r.actor}
		var analysis object
		if sourceVersion == 0 {
			analysis = r.write("create-analysis-dimension", payload)
		} else {
			analysis = r.write("revise-analysis-dimension", object{"objectId": str(subject["id"]) + "." + kind, "sourceVersion": sourceVersion, "definition": payload})
		}
		analyses[kind] = analysis
		evidenceBindings := array{}
		for _, table := range orderTables {
			binding := obj(obj(model.bindings[table])[kind])
			evidenceBindings = append(evidenceBindings, object{"bindingId": binding["id"], "bindingVersion": binding["version"], "eventId": binding["eventId"], "eventVersion": binding["eventVersion"], "logicUnitId": binding["logicUnitId"], "logicUnitVersion": binding["logicUnitVersion"]})
		}
		alignment := r.write("create-analysis-dimension-value-alignment", object{"analysisDimensionId": analysis["id"], "analysisDimensionVersion": version, "levelCode": level, "alignmentStatus": "PROVEN_SAME_KEY", "evidenceJson": string(encoded(object{"dimensionId": dimension, "dimensionVersion": 1, "valueType": valueType, "bindings": evidenceBindings, "basis": basis})), "confirmedBy": r.actor})
		mapped := object{}
		for _, table := range orderTables {
			binding := obj(obj(model.bindings[table])[kind])
			payload := object{"analysisDimensionId": analysis["id"], "analysisDimensionVersion": version, "levelCode": level, "bindingId": binding["id"], "bindingVersion": binding["version"], "alignmentStatus": "PROVEN_SAME_KEY", "alignmentId": alignment["id"]}
			mapping := r.write("create-analysis-dimension-event-mapping", payload)
			role := "business_time"
			if kind == "customer" {
				role = "customer"
			}
			mapped[table] = object{"reference": object{"id": analysis["id"], "version": fmt.Sprint(version), "levelCode": level}, "role": role, "mappingIds": array{mapping["id"]}}
			if r.declared != nil && r.report["mappingConflict"] == nil {
				mappingConflict(r, payload, mapping)
			}
		}
		mappings[kind] = mapped
	}
	customer := obj(obj(mappings["customer"])["orders"])
	adopt := func(code, key string, bound object, metricVersion int) object {
		return r.write("create-subject-area-metric-ref", object{"subjectAreaId": subject["id"], "subjectAreaVersion": version, "code": code, "displayName": code, "businessMeaning": meaning(code), "metricId": obj(model.definitions[key])["id"], "metricVersion": metricVersion, "bindings": bound, "fixedArguments": object{}, "selectedBy": r.actor})
	}
	children := object{}
	for _, entry := range []struct{ code, table string }{{"gross", "payments"}, {"refund", "refunds"}, {"orders", "orders"}, {"fee", "fees"}} {
		children[entry.code] = adopt(entry.code+"_original", entry.code, bindings(object{"customer": obj(mappings["customer"])[entry.table], "time": obj(mappings["original"])[entry.table]}, nil, nil), 1)
		if full && (entry.code == "gross" || entry.code == "refund") {
			children[entry.code+"_occurrence"] = adopt(entry.code+"_occurrence", entry.code, bindings(object{"customer": obj(mappings["customer"])[entry.table], "time": obj(mappings["occurrence"])[entry.table]}, nil, nil), 1)
		}
	}
	netInputs := object{"gross": children["gross"], "refund": children["refund"]}
	if netVersion == 2 {
		netInputs["fee"] = children["fee"]
	}
	net := adopt("original_net", "net", bindings(object{"customer": customer}, nil, netInputs), netVersion)
	total := adopt("original_total", "net_total", bindings(object{"customer": customer}, nil, object{"net": net}), 1)
	public := []object{total}
	var member object
	if full {
		public = append(public, net)
		occurrence := adopt("occurrence_net", "net", bindings(object{"customer": customer}, nil, object{"gross": children["gross_occurrence"], "refund": children["refund_occurrence"]}), 1)
		public = append(public, adopt("occurrence_total", "net_total", bindings(object{"customer": customer}, nil, object{"net": occurrence}), 1), adopt("gross_total", "net_total", bindings(object{"customer": customer}, nil, object{"net": children["gross"]}), 1))
		member = r.write("adopt-member-definition", object{"subjectAreaId": subject["id"], "subjectAreaVersion": version, "code": "qualified", "displayName": "本期合格客户", "definition": exactRef(memberDefinition), "bindings": bindings(object{"customer": customer}, nil, object{"net": net, "orders": children["orders"]}), "fixedArguments": object{}, "actor": r.actor})
		fixed := object{"kind": "SOURCE_CALL", "source": object{"id": member["id"], "version": version}, "arguments": object{"window": period("2026-01-01", "2026-02-01")}, "outputSlots": object{"customer": "customer"}, "members": object{}, "relations": object{}}
		rolling := merge(fixed, object{"arguments": object{"window": object{"kind": "ARGUMENT", "name": "window"}}})
		public = append(public, adopt("fixed_cohort", "qualified_total", bindings(object{"customer": customer}, object{"eligible": fixed}, object{"net": net}), 1), adopt("separate_cohort", "qualified_total", bindings(object{"customer": customer}, object{"eligible": rolling}, object{"net": net}), 1), adopt("fixed_growth", "growth", bindings(object{"customer": customer}, object{"eligible_source": fixed, "eligible": object{"kind": "LOCAL", "inputId": "fixed_people", "outputSlots": object{"customer": "customer"}}}, object{"net": net}), 1))
		for _, code := range []string{"order_mean", "customer_mean", "unweighted", "weighted"} {
			public = append(public, adopt(code, code, bindings(object{"customer": customer}, nil, object{"gross": children["gross"], "orders": children["orders"]}), 1))
		}
	}
	scenarioPayload := object{"subjectAreaId": subject["id"], "subjectAreaVersion": version, "code": "business", "displayName": "订单经营正式入口", "owner": r.actor, "description": "公开净额测量、客户名单与本次内联组合", "createdBy": r.actor}
	var scenario object
	if sourceVersion == 0 {
		scenario = r.write("create-scenario", scenarioPayload)
	} else {
		scenario = r.write("revise-scenario", object{"objectId": str(subject["id"]) + ".business", "sourceVersion": sourceVersion, "definition": scenarioPayload})
	}
	for index, adopted := range public {
		r.write("create-scenario-metric-ref", object{"scenarioId": scenario["id"], "scenarioVersion": version, "adoptionId": adopted["id"], "adoptionVersion": version, "requiredByDefault": true, "sortOrder": index + 1})
	}
	if full {
		r.write("create-scenario-member-source-ref", object{"scenarioId": scenario["id"], "scenarioVersion": version, "sourceId": member["id"], "sourceVersion": version, "permission": object{"outputs": array{"customer"}, "values": array{"customer"}, "filters": object{}, "timeRange": false, "clocks": array{}}})
		for index, operation := range []string{"JOIN_INPUT", "DERIVE", "PROJECT", "REGROUP"} {
			r.write("create-scenario-pipeline-rule", object{"scenarioId": scenario["id"], "scenarioVersion": version, "ruleType": operation, "sortOrder": index + 1})
		}
		for index, kind := range []string{"customer", "original"} {
			analysis := obj(analyses[kind])
			level := "day"
			if kind == "customer" {
				level = "customer"
			}
			r.write("create-scenario-analysis-dimension-ref", object{"scenarioId": scenario["id"], "scenarioVersion": version, "analysisDimensionId": analysis["id"], "analysisDimensionVersion": version, "defaultLevelCode": level, "requiredByDefault": true, "sortOrder": index + 1})
			binding := obj(obj(model.bindings["orders"])[kind])
			provider := r.write("create-analysis-dimension-value-provider", object{"analysisDimensionId": analysis["id"], "analysisDimensionVersion": version, "levelCode": level, "bindingId": binding["id"], "bindingVersion": binding["version"], "refreshPolicy": "ON_DEMAND", "createdBy": r.actor})
			r.write("create-scenario-filter-ref", object{"scenarioId": scenario["id"], "scenarioVersion": version, "filterTargetType": "ANALYSIS_DIMENSION", "targetId": analysis["id"], "targetVersion": version, "valueProviderId": provider["id"], "operators": array{"EQ"}, "requiredByDefault": false, "sortOrder": index + 1})
		}
	}
	if full {
		// 同一成员、同一主题域与发布，只改变候选值授权，不复制业务定义。
		restricted := r.write("create-scenario", object{"subjectAreaId": subject["id"], "subjectAreaVersion": version, "code": "member_without_values", "displayName": "成员输出可查询但不公开候选值", "owner": r.actor, "description": "保留输出授权，明确不授权values消费", "createdBy": r.actor})
		r.write("create-scenario-member-source-ref", object{"scenarioId": restricted["id"], "scenarioVersion": version, "sourceId": member["id"], "sourceVersion": version, "permission": object{"outputs": array{"customer"}, "values": array{}, "filters": object{}, "timeRange": false, "clocks": array{}}})
		r.report["memberWithoutValues"] = object{"scenarioId": restricted["id"], "scenarioVersion": version, "releaseId": "orders-business", "releaseVersion": releaseVersion}
	}
	return r.publish(subject, scenario, "orders-business", releaseVersion)
}

func buildOrders(r *remote, authored object) []object {
	model := buildOrdersBase(r, authored)
	member := r.write("create-member-definition", object{"code": "qualified_customer", "displayName": "两单且原单期净额至少100的客户", "definition": object{"kind": "COMPUTED", "calculation": authored["qualification"], "result": object{"entity": "客户", "role": "customer", "shape": "UNIQUE_MEMBERS", "identitySlots": array{"customer"}, "outputs": object{"customer": object{"kind": "COMPUTED_DIMENSION", "displayName": "客户", "role": "customer", "valueType": "TEXT", "dimensionSlot": "customer", "symbol": "customer", "valueContract": nil}}, "filterOperators": object{}, "timeSlot": nil, "clockSlots": object{}, "description": "至少两单且原单期净额至少100，不公开中间测量"}}, "description": "长期客户资格规则", "owner": r.actor, "createdBy": r.actor})
	r.activate("MEMBER_DEFINITION", member)
	payload := object{"businessLineId": model.line["id"], "businessLineVersion": 1, "code": "commerce", "displayName": "订单经营口径", "owner": r.actor, "description": "原单期、发生期与固定同群", "createdBy": r.actor}
	subject := r.write("create-subject-area", payload)
	entries := []object{orderContext(r, subject, model, member, 1, true, "1", 0)}
	definition := object{"code": "net", "displayName": "net", "owner": r.actor, "definition": obj(authored["definitions"])["net_v2"], "description": "可组合的订单语义定义", "businessMeaning": meaning("net"), "createdBy": r.actor}
	revised := r.write("revise-metric-calculation", object{"objectId": obj(model.definitions["net"])["id"], "sourceVersion": 1, "definition": definition})
	r.activate("METRIC", revised)
	revisedSubject := r.write("revise-subject-area", object{"objectId": subject["id"], "sourceVersion": 1, "definition": payload,
		"carry": object{"metrics": []any{}, "members": []any{}, "dimensions": []any{}, "scenarios": []any{}}})
	entries = append(entries, orderContext(r, revisedSubject, model, member, 2, false, "2", 1))
	retained := r.write("create-subject-area", merge(payload, object{"code": "retained", "displayName": "独立保留原口径"}))
	entries = append(entries, orderContext(r, retained, model, member, 1, false, "3", 0))
	write(filepath.Join(r.output, "publications.json"), entries)
	return entries
}

func mappingConflict(r *remote, payload, mapping object) {
	selection := object{"id": payload["analysisDimensionId"], "version": payload["analysisDimensionVersion"], "offset": 0, "limit": 200}
	before := r.read("read-analysis-dimension-mappings", selection)
	duplicate := r.callStatus("write-governance", object{"operation": "create-analysis-dimension-event-mapping", "payload": payload}, false)
	problem := obj(duplicate["error"])
	details := obj(problem["details"])
	require(problem["code"] == "RESOURCE_CONFLICT" && details["resourceId"] == mapping["id"] && details["analysisDimensionId"] == payload["analysisDimensionId"] && details["levelCode"] == payload["levelCode"], "重复映射缺少安全精确冲突事实")
	require(details["analysisDimensionVersion"] == payload["analysisDimensionVersion"] && details["readOperation"] == "read-analysis-dimension-mappings", "冲突没有给出原精确版本与可操作读取入口")
	existing := obj(details["existingMapping"])
	require(existing["bindingId"] == payload["bindingId"] && existing["bindingVersion"] == payload["bindingVersion"], "冲突没有指向实际已占用的绑定")
	require(string(encoded(before)) == string(encoded(r.read("read-analysis-dimension-mappings", selection))), "重复映射改变了原定义")
	invalid := r.callStatus("write-governance", object{"operation": "create-analysis-dimension-event-mapping", "payload": merge(payload, object{"analysisDimensionVersion": "not-a-version"})}, false)
	require(string(encoded(obj(obj(invalid["error"])["details"])["fieldPath"])) == `["analysisDimensionVersion"]`, "非法输入缺少精确字段路径")
	require(!strings.Contains(string(encoded(invalid["error"])), "com.olap"), "输入错误泄露内部Java类名")
	public := string(encoded(problem))
	require(!strings.Contains(public, "INSERT INTO") && !strings.Contains(public, "org.h2"), "冲突泄露内部SQL")
	r.report["mappingConflict"] = problem
}
