package com.olap.prototype.query.definition

import com.olap.prototype.query.contract.QueryDefinitionView

interface QueryDefinitionRepository {
    fun save(command: QueryDefinitionCommand, context: QueryDefinitionWriteContext): QueryDefinitionView
    fun update(definitionId: String, sourceVersion: Long, command: QueryDefinitionCommand, context: QueryDefinitionWriteContext): QueryDefinitionView
    fun find(scenarioId: String?, scenarioVersion: Long?, releaseId: String?, releaseVersion: String?): List<QueryDefinitionView>
    fun findByIdAndVersion(definitionId: String, definitionVersion: Long): QueryDefinitionView?
}
