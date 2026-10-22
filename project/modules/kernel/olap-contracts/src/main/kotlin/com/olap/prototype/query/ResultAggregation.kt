package com.olap.prototype.query

import com.olap.prototype.metric.MetricValueContract

/** 完成结果的聚合语言；数量声明、NULL 政策与舍入只存在于允许它们的聚合中。 */
sealed interface ResultAggregation {
    val kind: Kind
    val code: String get() = kind.name

    enum class Kind { SUM, AVG, STDDEV_SAMP, MIN, MAX, COUNT, COUNT_DISTINCT, ARRAY_AGG, ARRAY_AGG_DISTINCT }

    enum class NullPolicy { IGNORE, PROPAGATE }

    sealed interface Scalar : ResultAggregation { val nullPolicy: NullPolicy }
    sealed interface Numeric : Scalar { val valueContract: MetricValueContract }

    data class Sum(override val valueContract: MetricValueContract, override val nullPolicy: NullPolicy) : Numeric {
        override val kind: Kind get() = Kind.SUM
    }
    data class Average(override val valueContract: MetricValueContract, override val nullPolicy: NullPolicy,
        val rounding: MetricValueContract.Rounding) : Numeric {
        override val kind: Kind get() = Kind.AVG
    }
    data class SampleDeviation(override val valueContract: MetricValueContract, override val nullPolicy: NullPolicy) : Numeric {
        override val kind: Kind get() = Kind.STDDEV_SAMP
        init {
            require(valueContract.arithmetic !is MetricValueContract.Exact) { "STDDEV_SAMP requires explicit native floating or approximate arithmetic" }
        }
    }
    data class Minimum(override val nullPolicy: NullPolicy) : Scalar { override val kind: Kind get() = Kind.MIN }
    data class Maximum(override val nullPolicy: NullPolicy) : Scalar { override val kind: Kind get() = Kind.MAX }
    data class Count(val distinct: Boolean, override val nullPolicy: NullPolicy) : Scalar {
        override val kind: Kind get() = if (distinct) Kind.COUNT_DISTINCT else Kind.COUNT
    }
    data class Collect(val distinct: Boolean) : ResultAggregation {
        override val kind: Kind get() = if (distinct) Kind.ARRAY_AGG_DISTINCT else Kind.ARRAY_AGG
    }

    fun mapContracts(transform: (MetricValueContract) -> MetricValueContract): ResultAggregation = when (this) {
        is Sum -> copy(valueContract = transform(valueContract))
        is Average -> copy(valueContract = transform(valueContract))
        is SampleDeviation -> copy(valueContract = transform(valueContract))
        is Minimum, is Maximum, is Count, is Collect -> this
    }
}
