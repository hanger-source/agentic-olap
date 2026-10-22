package scenario

import "strings"

func sourceCall(source object, outputs ...string) object {
	slots := object{}
	for _, name := range outputs {
		slots[name] = name
	}
	return object{"kind": "SOURCE_CALL", "source": exactRef(source), "arguments": object{}, "outputSlots": slots, "members": object{}, "relations": object{}}
}
func simpleContext(r *remote, code, name, description string) (object, object) {
	line := r.write("create-business-line", object{"code": "example", "displayName": name, "owner": r.actor, "description": description, "createdBy": r.actor})
	r.activate("BUSINESS_LINE", line)
	subject := r.write("create-subject-area", object{"businessLineId": line["id"], "businessLineVersion": 1, "code": code, "displayName": name, "owner": r.actor, "description": description, "createdBy": r.actor})
	return line, subject
}
func memberSource(r *remote, subject object, code string, definition any, bound, fixed object) object {
	if fixed == nil {
		fixed = object{}
	}
	return r.write("create-member-source", object{"subjectAreaId": subject["id"], "subjectAreaVersion": subject["version"], "code": code, "displayName": code, "definition": definition, "bindings": bound, "fixedArguments": fixed, "actor": r.actor})
}
func adoption(r *remote, subject object, code string, definition object, bound, fixed object) object {
	if fixed == nil {
		fixed = object{}
	}
	return r.write("create-subject-area-metric-ref", object{"subjectAreaId": subject["id"], "subjectAreaVersion": subject["version"], "code": code, "displayName": code, "businessMeaning": meaning(code), "metricId": definition["id"], "metricVersion": definition["version"], "bindings": bound, "fixedArguments": fixed, "selectedBy": r.actor})
}
func scenarioMetrics(r *remote, scenario object, adopted []object) {
	for index, metric := range adopted {
		r.write("create-scenario-metric-ref", object{"scenarioId": scenario["id"], "scenarioVersion": scenario["version"], "adoptionId": metric["id"], "adoptionVersion": obj(metric["owner"])["version"], "requiredByDefault": true, "sortOrder": index + 1})
	}
}
func pipeline(r *remote, scenario object, operations ...string) {
	for index, operation := range operations {
		r.write("create-scenario-pipeline-rule", object{"scenarioId": scenario["id"], "scenarioVersion": scenario["version"], "ruleType": operation, "sortOrder": index + 1})
	}
}

func buildProcurement(r *remote, authored object) object {
	source := r.source("example-procurement")
	assets := object{}
	for _, table := range []string{"purchase_order", "receipt_delivery"} {
		types := map[string]string{"po_id": "STRING", "amount": "NUMBER"}
		key, timeColumn := "po_id", ""
		if table == "receipt_delivery" {
			types["delivery_id"] = "STRING"
			types["receipt_id"] = "STRING"
			types["revision"] = "NUMBER"
			types["status"] = "STRING"
			types["delivered_at"] = "LOCAL_TIMESTAMP"
			key = "delivery_id"
			timeColumn = "delivered_at"
		}
		logical, units := r.adoptTable(source, table, types, key, timeColumn, "procurement", false)
		topology := r.write("create-topology", object{"code": table, "displayName": table, "topologyImplementationNamespaceCode": "example.procurement", "topologyImplementationNamespaceDisplayName": "采购收货实现空间", "backboneLogicalTableId": logical["id"], "backboneLogicalTableVersion": 1, "owner": r.actor, "description": "独立事实保持原始投递、版本和取消记录", "createdBy": r.actor})
		r.activate("TOPOLOGY", topology)
		var timeID, timeVersion any
		if timeColumn != "" {
			timeID = obj(units[timeColumn])["id"]
			timeVersion = 1
		}
		filters := array{}
		for name, unit := range units {
			if name != "amount" {
				filters = append(filters, logicRef(obj(unit)))
			}
		}
		event := r.write("create-event", object{"topologyId": topology["id"], "topologyVersion": 1, "code": "registered", "displayName": table, "owner": r.actor, "eventTimeLogicUnitId": timeID, "eventTimeLogicUnitVersion": timeVersion, "identity": object{"recordKey": array{logicRef(obj(units[key]))}, "entityRoles": object{"purchase_order": array{logicRef(obj(units["po_id"]))}}}, "roles": object{}, "filterableLogicUnitRefs": filters, "measureLogicUnitRefs": array{logicRef(obj(units["amount"]))}, "description": "投递身份不代替收货或采购单身份", "createdBy": r.actor})
		r.activate("EVENT", event)
		assets[table] = object{"event": event, "units": units}
	}
	_, subject := simpleContext(r, "procurement", "采购与收货", "采购独立测量；保留投递、版本和取消历史")
	dimensionID := "example.purchase_order"
	d := r.write("create-dimension", object{"code": dimensionID, "dimensionCodeNamespaceDisplayName": "采购公共语义", "displayName": "采购单身份", "owner": r.actor, "description": "同一采购系统完整键", "createdBy": r.actor})
	require(d["id"] == dimensionID, "采购语义身份须与作者计算一致")
	r.activate("DIMENSION", d)
	analysis := r.write("create-analysis-dimension", object{"subjectAreaId": subject["id"], "subjectAreaVersion": 1, "code": "po", "displayName": "采购单", "description": "独立测量观察键", "levelCode": "po", "levelDisplayName": "采购单", "createdBy": r.actor})
	eventBindings := object{}
	evidence := array{}
	for _, table := range []string{"purchase_order", "receipt_delivery"} {
		asset := obj(assets[table])
		binding := r.write("create-dimension-event-binding", object{"dimensionId": d["id"], "dimensionVersion": 1, "eventId": obj(asset["event"])["id"], "eventVersion": 1, "logicUnitId": obj(obj(asset["units"])["po_id"])["id"], "logicUnitVersion": 1, "expressionJson": nil, "accessPath": object{"joinPathRefs": array{}}, "roles": object{}, "createdBy": r.actor})
		r.activate("DIMENSION_EVENT_BINDING", binding)
		observed := r.asset("DIMENSION_EVENT_BINDING", binding)
		eventBindings[table] = observed
		evidence = append(evidence, object{"bindingId": binding["id"], "bindingVersion": 1, "eventId": observed["eventId"], "eventVersion": observed["eventVersion"], "logicUnitId": observed["logicUnitId"], "logicUnitVersion": observed["logicUnitVersion"]})
	}
	alignment := r.write("create-analysis-dimension-value-alignment", object{"analysisDimensionId": analysis["id"], "analysisDimensionVersion": 1, "levelCode": "po", "alignmentStatus": "PROVEN_SAME_KEY", "evidenceJson": string(encoded(object{"dimensionId": d["id"], "dimensionVersion": 1, "valueType": "TEXT", "bindings": evidence, "basis": "明确两个源使用同一采购系统完整键，不按同名推断"})), "confirmedBy": r.actor})
	mappingIDs := array{}
	for _, table := range []string{"purchase_order", "receipt_delivery"} {
		mapping := r.write("create-analysis-dimension-event-mapping", object{"analysisDimensionId": analysis["id"], "analysisDimensionVersion": 1, "levelCode": "po", "bindingId": obj(eventBindings[table])["id"], "bindingVersion": 1, "alignmentStatus": "PROVEN_SAME_KEY", "alignmentId": alignment["id"]})
		mappingIDs = append(mappingIDs, mapping["id"])
	}
	po := object{"reference": object{"id": analysis["id"], "version": "1", "levelCode": "po"}, "role": "purchase_order", "mappingIds": mappingIDs}
	purchase := memberSource(r, subject, "purchase_rows", procurementProjection(obj(assets["purchase_order"]), obj(eventBindings["purchase_order"]), authored["money"], false), bindings(nil, nil, nil), nil)
	deliveries := memberSource(r, subject, "delivery_history", procurementProjection(obj(assets["receipt_delivery"]), obj(eventBindings["receipt_delivery"]), authored["money"], true), bindings(nil, nil, nil), nil)
	formed := object{"purchase": purchase}
	for _, code := range []string{"latest_effective", "latest_successful"} {
		formed[code] = memberSource(r, subject, code, authored[code], bindings(object{"po": po}, object{"deliveries": sourceCall(deliveries, "id", "receipt", "po", "revision", "status", "amount", "delivered")}, nil), object{"accepted": object{"kind": "SCALAR", "scalarType": "TEXT", "value": "SUCCESS"}})
	}
	definitions := object{"sum": r.calculation("record_amount_by_po", obj(authored["sum"])), "total": r.calculation("measurement_total", obj(authored["total"]))}
	adopted := []object{}
	for _, code := range []string{"purchase", "latest_effective", "latest_successful"} {
		measured := adoption(r, subject, code, obj(definitions["sum"]), bindings(object{"po": po}, object{"rows": sourceCall(obj(formed[code]), "id", "po", "amount")}, nil), nil)
		adopted = append(adopted, measured, adoption(r, subject, code+"_total", obj(definitions["total"]), bindings(object{"po": po}, nil, object{"amount": measured}), nil))
	}
	scenario := r.write("create-scenario", object{"subjectAreaId": subject["id"], "subjectAreaVersion": 1, "code": "receipt_comparison", "displayName": "采购与两种收货口径", "owner": r.actor, "description": "采购人口为基准；未匹配保持NULL", "createdBy": r.actor})
	scenarioMetrics(r, scenario, adopted)
	r.write("create-scenario-analysis-dimension-ref", object{"scenarioId": scenario["id"], "scenarioVersion": 1, "analysisDimensionId": analysis["id"], "analysisDimensionVersion": 1, "defaultLevelCode": "po", "requiredByDefault": true, "sortOrder": 1})
	provider := r.write("create-analysis-dimension-value-provider", object{"analysisDimensionId": analysis["id"], "analysisDimensionVersion": 1, "levelCode": "po", "bindingId": obj(eventBindings["purchase_order"])["id"], "bindingVersion": 1, "refreshPolicy": "ON_DEMAND", "createdBy": r.actor})
	r.write("create-scenario-filter-ref", object{"scenarioId": scenario["id"], "scenarioVersion": 1, "filterTargetType": "ANALYSIS_DIMENSION", "targetId": analysis["id"], "targetVersion": 1, "valueProviderId": provider["id"], "operators": array{"EQ"}, "requiredByDefault": false, "sortOrder": 1})
	return r.publish(subject, scenario, "procurement-observation", "1")
}
func procurementProjection(asset, binding object, money any, deliveries bool) object {
	property := func(column, role, valueType string, contract any) object {
		return object{"kind": "PROPERTY", "displayName": column, "role": role, "valueType": valueType, "logicUnitId": obj(obj(asset["units"])[column])["id"], "logicUnitVersion": 1, "accessPath": object{"joinPathRefs": array{}}, "valueContract": contract}
	}
	id, role, entity, memberRole := "po_id", "record_identity", "业务金额记录", "amount_record"
	if deliveries {
		id = "delivery_id"
		role = "delivery_identity"
		entity = "收货投递"
		memberRole = "delivery"
	}
	outputs := object{"id": property(id, role, "TEXT", nil), "po": object{"kind": "DIMENSION", "displayName": "采购单", "role": "purchase_order", "valueType": "TEXT", "dimensionId": "example.purchase_order", "dimensionVersion": 1, "bindingId": binding["id"], "bindingVersion": 1, "valueContract": nil}, "amount": property("amount", "amount", "NUMBER", money)}
	if deliveries {
		for _, entry := range []struct{ slot, column, role, valueType string }{{"receipt", "receipt_id", "record_identity", "TEXT"}, {"revision", "revision", "version", "NUMBER"}, {"status", "status", "status", "TEXT"}, {"delivered", "delivered_at", "delivery_time", "LOCAL_TIMESTAMP"}} {
			outputs[entry.slot] = property(entry.column, entry.role, entry.valueType, nil)
		}
	}
	return object{"kind": "PROJECTION", "event": object{"eventId": obj(asset["event"])["id"], "eventVersion": 1}, "result": object{"entity": entity, "role": memberRole, "shape": "RECORDS", "identitySlots": array{"id"}, "outputs": outputs, "filterOperators": object{}, "timeSlot": nil, "clockSlots": object{}, "description": "每次真实投递一行，不合并重复或状态历史"}}
}
func procurementQueries(publication object) []query {
	capability := obj(publication["capability"])
	result := []query{}
	for _, scalar := range []bool{false, true} {
		suffix, label := "", "procurement_by_po"
		grouping := array{dimension(capability, "po", "po")}
		if scalar {
			suffix = "_total"
			label = "procurement_totals"
			grouping = array{}
		}
		calls := array{}
		for _, code := range []string{"purchase", "latest_effective", "latest_successful"} {
			calls = append(calls, measure(capability, code+suffix, code, code, object{}))
		}
		root := measures(calls, grouping)
		root["population"] = object{"mode": "CALLS", "callIds": array{"purchase"}}
		result = append(result, query{label, intent(capability, root)})
	}
	return result
}
func verifyProcurement(label string, names []string, rows array) {
	scalar := strings.TrimPrefix(label, "saved:") == "procurement_totals"
	columns := 4
	if scalar {
		columns = 3
	}
	require(len(names) == columns, "采购结果须保明确观察键及三个独立测量")
	expected := map[string][]string{"P-001": {"100", "80", "80"}, "P-002": {"150", "50", "120"}}
	if scalar {
		expected = map[string][]string{"total": {"250", "130", "200"}}
	}
	seen := map[string]bool{}
	for _, row := range rows {
		values := object{}
		for index, name := range names {
			values[name] = arr(row)[index]
		}
		key := "total"
		if !scalar {
			for _, name := range names {
				if name != "purchase" && name != "latest_effective" && name != "latest_successful" {
					key = str(obj(values[name])["value"])
				}
			}
		}
		require(!seen[key], "多条收货重复了采购单")
		seen[key] = true
		want, ok := expected[key]
		require(ok, "实际采购人口未声明：%s", key)
		for index, name := range []string{"purchase", "latest_effective", "latest_successful"} {
			verifyExact(label+":"+key+":"+name, array{array{values[name]}}, want[index])
		}
	}
	require(len(seen) == len(expected), "采购人口缺失")
}
