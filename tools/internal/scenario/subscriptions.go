package scenario

import (
	"math/big"
	"reflect"
	"strings"
)

var subscriptionTables = []string{"legacy_stock", "current_stock", "region_history", "usage_events", "calendar_days", "subscription_destinations", "subscription_charges"}

type subscriptionAxis struct {
	name, column string
	tables       []string
}

var subscriptionAxes = []subscriptionAxis{
	{"subscription", "subscription", []string{"legacy_stock", "current_stock", "region_history", "subscription_destinations", "subscription_charges"}},
	{"day", "observed", []string{"legacy_stock", "current_stock", "region_history", "usage_events", "calendar_days", "subscription_charges"}},
	{"region", "region", []string{"region_history"}}, {"destination", "destination", []string{"subscription_destinations"}},
}

func subscriptionTypes(table string) map[string]string {
	types := map[string]string{"id": "NUMBER"}
	if table != "subscription_destinations" {
		types["observed"] = "DATE"
	}
	switch table {
	case "legacy_stock", "current_stock", "subscription_charges":
		types["subscription"] = "STRING"
		types["amount"] = "NUMBER"
	case "region_history":
		types["subscription"] = "STRING"
		types["region"] = "STRING"
	case "usage_events":
		types["tenant"] = "STRING"
		types["person"] = "STRING"
	case "subscription_destinations":
		types["subscription"] = "STRING"
		types["destination"] = "STRING"
	}
	return types
}
func subscriptionMemberCall(source object, arguments object) object {
	outputs := obj(obj(obj(source["definition"])["result"])["outputs"])
	slots := object{}
	for name := range outputs {
		slots[name] = name
	}
	if arguments == nil {
		arguments = object{}
	}
	return object{"kind": "SOURCE_CALL", "source": exactRef(source), "arguments": arguments, "outputSlots": slots, "members": object{}, "relations": object{}}
}

func buildSubscriptions(r *remote, authored object) object {
	source := r.source("example-subscriptions")
	units, events := object{}, object{}
	for _, table := range subscriptionTables {
		timeColumn := "observed"
		if table == "subscription_destinations" {
			timeColumn = ""
		}
		types := subscriptionTypes(table)
		logical, tableUnits := r.adoptTable(source, table, types, "id", timeColumn, "subscriptions", false)
		units[table] = tableUnits
		topology := r.write("create-topology", object{"code": table, "displayName": table, "topologyImplementationNamespaceCode": "example.subscriptions", "topologyImplementationNamespaceDisplayName": "订阅事实实现空间", "backboneLogicalTableId": logical["id"], "backboneLogicalTableVersion": 1, "owner": r.actor, "description": "独立真实源骨干", "createdBy": r.actor})
		r.activate("TOPOLOGY", topology)
		var timeID, timeVersion any
		if timeColumn != "" {
			timeID = obj(tableUnits[timeColumn])["id"]
			timeVersion = 1
		}
		filters, measured := array{}, array{}
		for column, kind := range types {
			if kind == "STRING" {
				filters = append(filters, logicRef(obj(tableUnits[column])))
			} else if kind == "NUMBER" {
				measured = append(measured, logicRef(obj(tableUnits[column])))
			}
		}
		event := r.write("create-event", object{"topologyId": topology["id"], "topologyVersion": 1, "code": "recorded", "displayName": table, "owner": r.actor, "eventTimeLogicUnitId": timeID, "eventTimeLogicUnitVersion": timeVersion, "identity": object{"recordKey": array{logicRef(obj(tableUnits["id"]))}, "entityRoles": object{}}, "roles": object{}, "filterableLogicUnitRefs": filters, "measureLogicUnitRefs": measured, "description": "原始历史；业务版本与来源选择分别定义", "createdBy": r.actor})
		r.activate("EVENT", event)
		events[table] = event
	}
	line := r.write("create-business-line", object{"code": "subscriptions", "displayName": "订阅经营", "owner": r.actor, "description": "明确历史与完整日历", "createdBy": r.actor})
	r.activate("BUSINESS_LINE", line)
	subject := r.write("create-subject-area", object{"businessLineId": line["id"], "businessLineVersion": 1, "code": "history", "displayName": "订阅历史观测", "owner": r.actor, "description": "先形成真实历史对象再观测", "createdBy": r.actor})
	analyses, eventBindings, mappings := object{}, object{}, object{}
	for _, axis := range subscriptionAxes {
		semantic := "subscriptions." + axis.name
		d := r.write("create-dimension", object{"code": semantic, "dimensionCodeNamespaceDisplayName": "订阅共同语义轴", "displayName": axis.name, "owner": r.actor, "description": axis.name, "createdBy": r.actor})
		require(d["id"] == semantic, "作者slot与语义身份不一致")
		r.activate("DIMENSION", d)
		bound := object{}
		evidence := array{}
		for _, table := range axis.tables {
			binding := r.write("create-dimension-event-binding", merge(logicRef(obj(obj(units[table])[axis.column])), object{"dimensionId": semantic, "dimensionVersion": 1, "eventId": obj(events[table])["id"], "eventVersion": 1, "expressionJson": nil, "accessPath": object{"joinPathRefs": array{}}, "roles": object{}, "createdBy": r.actor}))
			r.activate("DIMENSION_EVENT_BINDING", binding)
			full := r.asset("DIMENSION_EVENT_BINDING", binding)
			bound[table] = full
			evidence = append(evidence, object{"bindingId": full["id"], "bindingVersion": full["version"], "eventId": full["eventId"], "eventVersion": full["eventVersion"], "logicUnitId": full["logicUnitId"], "logicUnitVersion": full["logicUnitVersion"]})
		}
		eventBindings[axis.name] = bound
		analysis := r.write("create-analysis-dimension", object{"subjectAreaId": subject["id"], "subjectAreaVersion": 1, "code": axis.name, "displayName": axis.name, "description": "明确同一订阅标识、公历日期与区域代码", "levelCode": axis.name, "levelDisplayName": axis.name, "createdBy": r.actor})
		analyses[axis.name] = analysis
		valueType := "TEXT"
		if axis.name == "day" {
			valueType = "DATE"
		}
		alignment := r.write("create-analysis-dimension-value-alignment", object{"analysisDimensionId": analysis["id"], "analysisDimensionVersion": 1, "levelCode": axis.name, "alignmentStatus": "PROVEN_SAME_KEY", "confirmedBy": r.actor, "evidenceJson": string(encoded(object{"dimensionId": semantic, "dimensionVersion": 1, "valueType": valueType, "basis": "作者原始表使用同一业务身份与公历日期，未换义", "bindings": evidence}))})
		mappingIDs := array{}
		for _, table := range axis.tables {
			binding := obj(bound[table])
			mapping := r.write("create-analysis-dimension-event-mapping", object{"analysisDimensionId": analysis["id"], "analysisDimensionVersion": 1, "levelCode": axis.name, "bindingId": binding["id"], "bindingVersion": binding["version"], "alignmentStatus": "PROVEN_SAME_KEY", "alignmentId": alignment["id"]})
			mappingIDs = append(mappingIDs, mapping["id"])
		}
		mappings[axis.name] = object{"reference": object{"id": analysis["id"], "version": "1", "levelCode": axis.name}, "role": axis.name, "mappingIds": mappingIDs}
	}
	members := object{}
	for _, entry := range []struct{ code, table string }{{"legacy", "legacy_stock"}, {"current", "current_stock"}, {"regions", "region_history"}, {"usage", "usage_events"}, {"calendar", "calendar_days"}, {"destinations", "subscription_destinations"}} {
		result := obj(clone(obj(authored["rowContracts"])[entry.code]))
		outputs := object{}
		for name, value := range obj(result["outputs"]) {
			output := obj(value)
			switch output["kind"] {
			case "COMPUTED_DIMENSION":
				binding := obj(obj(eventBindings[name])[entry.table])
				outputs[name] = object{"kind": "DIMENSION", "displayName": name, "role": output["role"], "valueType": output["valueType"], "dimensionId": "subscriptions." + name, "dimensionVersion": 1, "bindingId": binding["id"], "bindingVersion": 1, "valueContract": output["valueContract"]}
			case "COMPUTED_PROPERTY":
				outputs[name] = merge(logicRef(obj(obj(units[entry.table])[name])), object{"kind": "PROPERTY", "displayName": name, "role": output["role"], "valueType": output["valueType"], "accessPath": object{"joinPathRefs": array{}}, "valueContract": output["valueContract"]})
			default:
				require(false, "作者记录输出类型未知：%v", output["kind"])
			}
		}
		result["outputs"] = outputs
		created := memberSource(r, subject, str(result["entity"]), object{"kind": "PROJECTION", "event": object{"eventId": obj(events[entry.table])["id"], "eventVersion": 1}, "result": result}, bindings(nil, nil, nil), nil)
		members[entry.code] = r.asset("MEMBER_SOURCE", created)
	}
	for _, entry := range []struct {
		code    string
		sources map[string]string
	}{{"selected_stock", map[string]string{"legacy": "legacy", "current": "current"}}, {"historical_region", map[string]string{"regions": "regions"}}, {"historical_stock", map[string]string{"stock": "selected_stock", "regions": "historical_region"}}} {
		definition := obj(obj(authored["members"])[entry.code])
		shared := r.write("create-member-definition", object{"code": entry.code, "displayName": entry.code, "owner": r.actor, "definition": definition, "description": obj(definition["result"])["description"], "createdBy": r.actor})
		r.activate("MEMBER_DEFINITION", shared)
		calls := object{}
		for slot, name := range entry.sources {
			args := object{}
			if entry.code == "historical_stock" {
				args["as_of"] = object{"kind": "ARGUMENT", "name": "as_of"}
			}
			calls[slot] = subscriptionMemberCall(obj(members[name]), args)
		}
		dimensions := object{}
		for _, slot := range arr(obj(definition["calculation"])["dimensionSlots"]) {
			name := str(obj(slot)["name"])
			dimensions[name] = mappings[name]
		}
		adopted := r.write("adopt-member-definition", object{"subjectAreaId": subject["id"], "subjectAreaVersion": 1, "code": entry.code, "displayName": entry.code, "definition": exactRef(shared), "bindings": bindings(dimensions, calls, nil), "fixedArguments": object{}, "actor": r.actor})
		members[entry.code] = r.asset("MEMBER_SOURCE", adopted)
	}
	charges := r.write("create-metric", object{"sourceEventId": obj(events["subscription_charges"])["id"], "sourceEventVersion": 1, "code": "confirmed_charges", "displayName": "实际确认账款", "owner": r.actor, "inputExpressionJson": string(encoded(logic(obj(obj(units["subscription_charges"])["amount"])))), "aggregation": "SUM", "distinct": false, "metricKind": "BASE", "mergePolicy": "ADDITIVE", "privatePredicateJson": nil, "roles": object{}, "snapshotRule": nil, "description": "确认账款为独立可加事实，历史存量不是账款", "businessMeaning": meaning("真实确认账款总额"), "valueContract": authored["balance"], "aggregationRounding": "NONE", "createdBy": r.actor})
	r.activate("METRIC", charges)
	definitions := object{}
	for code, value := range obj(authored["definitions"]) {
		definition := obj(clone(value))
		if code == "charges_by_subscription" {
			target := obj(obj(arr(obj(obj(obj(definition["calculation"])["computation"])["root"])["calls"])[0])["target"])
			require(target["kind"] == "FIXED" && obj(target["metric"])["id"] == "authored.charges", "作者账款事实位置改变")
			target["metric"] = object{"id": charges["id"], "version": "1"}
		}
		definitions[code] = r.calculation(code, definition)
	}
	chargesAdoption := adoption(r, subject, "charges_by_subscription", obj(definitions["charges_by_subscription"]), bindings(object{"subscription": mappings["subscription"]}, nil, nil), nil)
	balanceBindings := bindings(object{"subscription": mappings["subscription"], "day": mappings["day"], "region": mappings["region"]}, object{"records": subscriptionMemberCall(obj(members["historical_stock"]), object{"as_of": object{"kind": "ARGUMENT", "name": "as_of"}})}, nil)
	balanceBindings["groupingCandidates"] = object{"groups": array{mappings["region"], mappings["subscription"]}}
	fixedBalance := func(date string, groups array) object {
		return object{"as_of": object{"kind": "SCALAR", "scalarType": "DATE", "value": date}, "groups": object{"kind": "GROUPING", "dimensions": groups}}
	}
	adopted := []object{adoption(r, subject, "subscription_balances", obj(definitions["historical_balance"]), balanceBindings, fixedBalance("2026-04-30", array{obj(mappings["subscription"])["reference"]})), chargesAdoption}
	for _, entry := range []struct {
		code, date string
		grouped    bool
	}{{"march_regions", "2026-03-31", true}, {"april_regions", "2026-04-30", true}, {"april_total", "2026-04-30", false}} {
		groups := array{}
		if entry.grouped {
			groups = append(groups, obj(mappings["region"])["reference"])
		}
		adopted = append(adopted, adoption(r, subject, entry.code, obj(definitions["historical_balance"]), balanceBindings, fixedBalance(entry.date, groups)))
	}
	windowBindings := bindings(object{"day": mappings["day"]}, object{"usage": subscriptionMemberCall(obj(members["usage"]), nil), "calendar": subscriptionMemberCall(obj(members["calendar"]), nil)}, nil)
	adopted = append(adopted, adoption(r, subject, "seven_day_people", obj(definitions["window_people"]), windowBindings, nil), adoption(r, subject, "calendar_daily_mean", obj(definitions["calendar_daily_mean"]), windowBindings, nil), adoption(r, subject, "allocated_destinations", obj(definitions["destination_allocation"]), bindings(object{"subscription": mappings["subscription"], "destination": mappings["destination"]}, object{"destinations": subscriptionMemberCall(obj(members["destinations"]), nil)}, object{"charges": chargesAdoption}), nil))
	scenario := r.write("create-scenario", object{"subjectAreaId": subject["id"], "subjectAreaVersion": 1, "code": "observation", "displayName": "订阅历史和日历入口", "owner": r.actor, "description": "正式公开采用", "createdBy": r.actor})
	for index, metric := range adopted {
		r.write("create-scenario-metric-ref", object{"scenarioId": scenario["id"], "scenarioVersion": 1, "adoptionId": metric["id"], "adoptionVersion": 1, "requiredByDefault": false, "sortOrder": index + 1})
	}
	for index, axis := range subscriptionAxes {
		r.write("create-scenario-analysis-dimension-ref", object{"scenarioId": scenario["id"], "scenarioVersion": 1, "analysisDimensionId": obj(analyses[axis.name])["id"], "analysisDimensionVersion": 1, "defaultLevelCode": axis.name, "requiredByDefault": false, "sortOrder": index + 1})
	}
	provider := r.write("create-analysis-dimension-value-provider", object{"analysisDimensionId": obj(analyses["region"])["id"], "analysisDimensionVersion": 1, "levelCode": "region", "bindingId": obj(obj(eventBindings["region"])["region_history"])["id"], "bindingVersion": 1, "refreshPolicy": "ON_DEMAND", "createdBy": r.actor})
	r.write("create-scenario-filter-ref", object{"scenarioId": scenario["id"], "scenarioVersion": 1, "filterTargetType": "ANALYSIS_DIMENSION", "targetId": obj(analyses["region"])["id"], "targetVersion": 1, "valueProviderId": provider["id"], "operators": array{"EQ"}, "requiredByDefault": false, "sortOrder": 1})
	return r.publish(subject, scenario, "subscription-history", "1")
}
func subscriptionQueries(publication object) []query {
	capability := obj(publication["capability"])
	queries := []query{}
	for _, code := range []string{"march_regions", "april_regions", "april_total", "seven_day_people", "calendar_daily_mean", "subscription_balances", "charges_by_subscription", "allocated_destinations"} {
		axis := "region"
		switch code {
		case "seven_day_people", "calendar_daily_mean":
			axis = "day"
		case "subscription_balances", "charges_by_subscription":
			axis = "subscription"
		case "allocated_destinations":
			axis = "destination"
		}
		grouping := array{}
		if code != "april_total" {
			grouping = append(grouping, dimension(capability, axis, axis))
		}
		queries = append(queries, query{code, intent(capability, measures(array{measure(capability, code, "observation", "observation", object{})}, grouping))})
	}
	return queries
}
func verifySubscriptions(label string, names []string, rows array) {
	label = strings.TrimPrefix(label, "saved:")
	expected := map[string]map[string]string{"march_regions": {"东": "150"}, "april_regions": {"东": "50", "南": "150"}, "april_total": {"": "200"}, "seven_day_people": {"2026-03-08": "3"}, "calendar_daily_mean": {"2026-03-08": "4/7"}, "subscription_balances": {"S1": "150", "S2": "50"}, "charges_by_subscription": {"S1": "150", "S2": "50"}, "allocated_destinations": {"东": "125", "西": "75"}}[label]
	require(expected != nil, "未知订阅独立预期：%s", label)
	seen := map[string]bool{}
	for _, value := range rows {
		row := arr(value)
		key := ""
		if label != "april_total" {
			key = str(obj(row[0])["value"])
		}
		require(!seen[key], "历史结果重复完整观察对象")
		seen[key] = true
		want, ok := expected[key]
		require(ok, "历史结果出现未声明观察对象：%s", key)
		if label == "calendar_daily_mean" {
			verifyScalarWithin(label+":"+key, array{array{row[len(row)-1]}}, want, big.NewRat(1, 1000000000))
		} else {
			verifyExact(label+":"+key, array{array{row[len(row)-1]}}, want)
		}
	}
	require(len(seen) == len(expected), "历史结果缺失观察对象")
}
func subscriptionCandidates(r *remote, capability object) {
	found := array{}
	for _, value := range arr(capability["filterCandidates"]) {
		candidate := obj(value)
		types, _ := candidate["filterValueTypes"].(map[string]any)
		if candidate["queryFieldKind"] == "DIMENSION" && types["region"] == "TEXT" {
			found = append(found, candidate)
		}
	}
	require(len(found) == 1, "区域须有唯一实际批准提供器")
	request := merge(coordinate(capability), object{"capabilityHash": capability["capabilityHash"], "target": object{"kind": "DIMENSION_PROVIDER", "filterRefId": obj(found[0])["filterRefId"], "levelCode": "region"}, "limit": 1})
	for offset, expected := range []string{"东", "南"} {
		page := obj(r.call("candidate-values", merge(request, object{"offset": offset})))
		require(reflect.DeepEqual(page["values"], array{expected}), "区域候选未保持真实值")
		if offset == 0 {
			require(integer(page["nextOffset"]) == 1, "候选分页未前进")
		} else {
			require(page["nextOffset"] == nil, "候选末页未结束")
		}
	}
}
