package com.olap.prototype.expression

import com.olap.prototype.connector.CompilationSupport
import org.apache.calcite.config.CalciteConnectionConfigImpl
import org.apache.calcite.jdbc.CalciteSchema
import org.apache.calcite.jdbc.JavaTypeFactoryImpl
import org.apache.calcite.plan.ConventionTraitDef
import org.apache.calcite.plan.RelOptCluster
import org.apache.calcite.plan.volcano.VolcanoPlanner
import org.apache.calcite.prepare.CalciteCatalogReader
import org.apache.calcite.rel.RelCollationTraitDef
import org.apache.calcite.rex.RexBuilder
import org.apache.calcite.schema.SchemaPlus
import org.apache.calcite.tools.Frameworks
import org.apache.calcite.tools.RelBuilder
import java.util.Properties

/** 一个精确来源拥有其 planner、类型工厂和 schema reader。 */
object CalciteCompilation {
    fun builder(support: CompilationSupport, schema: SchemaPlus = Frameworks.createRootSchema(false)): RelBuilder {
        val types = JavaTypeFactoryImpl(SemanticCalciteTypeSystem(support))
        val planner = VolcanoPlanner().apply {
            addRelTraitDef(ConventionTraitDef.INSTANCE)
            addRelTraitDef(RelCollationTraitDef.INSTANCE)
        }
        val cluster = RelOptCluster.create(planner, RexBuilder(types))
        cluster.setMetadataQuerySupplier { SemanticRelMetadataQuery(cluster) }
        val reader = CalciteCatalogReader(CalciteSchema.from(schema), emptyList(), types, CalciteConnectionConfigImpl(Properties()))
        return RelBuilder.proto().create(cluster, reader)
    }
}
