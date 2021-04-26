package `in`.specmatic.core.pattern

import io.cucumber.messages.Messages
import `in`.specmatic.core.*
import `in`.specmatic.core.utilities.mapZip
import `in`.specmatic.core.utilities.stringToPatternMap
import `in`.specmatic.core.utilities.withNullPattern
import `in`.specmatic.core.value.*

fun toTabularPattern(jsonContent: String, typeAlias: String? = null): TabularPattern = toTabularPattern(stringToPatternMap(jsonContent), typeAlias)

fun toTabularPattern(map: Map<String, Pattern>, typeAlias: String? = null): TabularPattern {
    val missingKeyStrategy = when ("...") {
        in map -> ignoreUnexpectedKeys
        else -> ::validateUnexpectedKeys
    }

    return TabularPattern(map.minus("..."), missingKeyStrategy, typeAlias)
}

data class TabularPattern(override val pattern: Map<String, Pattern>, private val unexpectedKeyCheck: UnexpectedKeyCheck = ::validateUnexpectedKeys, override val typeAlias: String? = null) : Pattern {
    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if (sampleData !is JSONObjectValue)
            return mismatchResult("JSON object", sampleData)

        val resolverWithNullType = withNullPattern(resolver)
        val missingKey = resolverWithNullType.findMissingKey(pattern, sampleData.jsonObject, unexpectedKeyCheck)
        if (missingKey != null)
            return missingKeyToResult(missingKey, "key")

        mapZip(pattern, sampleData.jsonObject).forEach { (key, patternValue, sampleValue) ->
            when (val result = resolverWithNullType.matchesPattern(key, patternValue, sampleValue)) {
                is Result.Failure -> return result.breadCrumb(key)
            }
        }

        return Result.Success()
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value = JSONArrayValue(valueList)

    override fun generate(resolver: Resolver): JSONObjectValue {
        val resolverWithNullType = withNullPattern(resolver)
        return JSONObjectValue(pattern.mapKeys { entry -> withoutOptionality(entry.key) }.mapValues { (key, pattern) ->
            attempt(breadCrumb = key) { resolverWithNullType.generate(key, pattern) }
        })
    }

    override fun newBasedOn(row: Row, resolver: Resolver): List<Pattern> {
        val resolverWithNullType = withNullPattern(resolver)
        return forEachKeyCombinationIn(pattern, row) { pattern ->
            newBasedOn(pattern, row, resolverWithNullType)
        }.map { toTabularPattern(it) }
    }

    override fun newBasedOn(resolver: Resolver): List<Pattern> {
        val resolverWithNullType = withNullPattern(resolver)
        val allOrNothingCombinationIn = allOrNothingCombinationIn(pattern) { pattern ->
            newBasedOn(pattern, resolverWithNullType)
        }
        return allOrNothingCombinationIn.map { toTabularPattern(it) }
    }

    override fun parse(value: String, resolver: Resolver): Value = parsedJSON(value)
    override fun encompasses(otherPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result {
        val thisResolverWithNullType = withNullPattern(thisResolver)
        val otherResolverWithNullType = withNullPattern(otherResolver)

        return when (otherPattern) {
            is ExactValuePattern -> otherPattern.fitsWithin(listOf(this), otherResolverWithNullType, thisResolverWithNullType, typeStack)
            !is TabularPattern -> Result.Failure("Expected tabular json type, got ${otherPattern.typeName}")
            else -> mapEncompassesMap(pattern, otherPattern.pattern, thisResolverWithNullType, otherResolverWithNullType, typeStack)
        }
    }

    override val typeName: String = "json object"
}

fun newBasedOn(patternMap: Map<String, Pattern>, row: Row, resolver: Resolver): List<Map<String, Pattern>> {
    val patternCollection = patternMap.mapValues { (key, pattern) ->
        attempt(breadCrumb = key) {
            newBasedOn(row, key, pattern, resolver)
        }
    }

    return patternList(patternCollection)
}

fun newBasedOn(patternMap: Map<String, Pattern>, resolver: Resolver): List<Map<String, Pattern>> {
    val patternCollection = patternMap.mapValues { (key, pattern) ->
        attempt(breadCrumb = key) {
            newBasedOn(pattern, resolver)
        }
    }

    return patternValues(patternCollection)
}

fun newBasedOn(row: Row, key: String, pattern: Pattern, resolver: Resolver): List<Pattern> {
    val keyWithoutOptionality = key(pattern, key)

    return when {
        row.containsField(keyWithoutOptionality) -> {
            val rowValue = row.getField(keyWithoutOptionality)
            if (isPatternToken(rowValue)) {
                val rowPattern = resolver.getPattern(rowValue)

                attempt(breadCrumb = key) {
                    when (val result = pattern.encompasses(rowPattern, resolver, resolver)) {
                        is Result.Success -> rowPattern.newBasedOn(row, resolver)
                        else -> throw ContractException(resultReport(result))
                    }
                }
            } else {
                val parsedRowValue = attempt("Format error in example of \"$keyWithoutOptionality\"") {
                    pattern.parse(rowValue, resolver)
                }

                when (val matchResult = pattern.matches(parsedRowValue, resolver)) {
                    is Result.Failure -> throw ContractException(resultReport(matchResult))
                    else -> listOf(ExactValuePattern(parsedRowValue))
                }
            }
        }
        else -> pattern.newBasedOn(row, resolver)
    }
}

fun newBasedOn(pattern: Pattern, resolver: Resolver): List<Pattern> {
    return pattern.newBasedOn(resolver)
}

fun key(pattern: Pattern, key: String): String {
    return withoutOptionality(when (pattern) {
        is Keyed -> pattern.key ?: key
        else -> key
    })
}

fun <ValueType> patternList(patternCollection: Map<String, List<ValueType>>): List<Map<String, ValueType>> {
    if (patternCollection.isEmpty())
        return listOf(emptyMap())

    val key = patternCollection.keys.first()

    return (patternCollection[key] ?: throw ContractException("key $key should not be empty in $patternCollection"))
            .flatMap { pattern ->
                val subLists = patternList(patternCollection - key)
                subLists.map { generatedPatternMap ->
                    generatedPatternMap.plus(Pair(key, pattern))
                }
            }
}

fun <ValueType> patternValues(patternCollection: Map<String, List<ValueType>>): List<Map<String, ValueType>> {
    if (patternCollection.isEmpty())
        return listOf(emptyMap())

    val optionalValues = patternCollection.filter { entry -> optionalValues(entry) }

    val optionalValuesSetToNull = optionalValues.map { entry ->
        Pair(entry.key, NullPattern as ValueType)
    }.toMap()

    val optionalValuesSetToValue = optionalValues.map { entry ->
        Pair(entry.key, entry.value.find { p ->
            p !is NullPattern
        } as ValueType)
    }.toMap()

    val singleValues = patternCollection.filter { entry -> !optionalValues(entry) }

    val parentsWithoutOptionalChildren = singleValues.filter { it.value.size == 1 }
    val parentsWithOptionalChildren = singleValues.filter { it.value.size > 1 }

    val parents = parentsWithoutOptionalChildren.map { entry ->
        Pair(entry.key, entry.value[0])
    }.toMap()

    val firstValuesSetToValues = parentsWithOptionalChildren.map { entry -> entry.key to entry.value[0] }.toMap()

    val secondValuesSetToValues = parentsWithOptionalChildren.map { entry -> entry.key to entry.value[1] }.toMap()

    val list = if (parentsWithOptionalChildren.isNotEmpty()) {
        listOf(parents.plus(firstValuesSetToValues), parents.plus(secondValuesSetToValues))
    } else {
        listOf(parents)
    }

    return if (patternCollection.any { entry -> optionalValues(entry) }) {
        list.map {
            listOf(optionalValuesSetToNull.plus(it), optionalValuesSetToValue.plus(it))
        }.flatten()
    } else {
        list
    }
}

private fun <ValueType> optionalValues(entry: Map.Entry<String, List<ValueType>>) =
        entry.value.size > 1 && entry.value.contains(NullPattern)

fun <ValueType> forEachKeyCombinationIn(patternMap: Map<String, ValueType>, row: Row, creator: (Map<String, ValueType>) -> List<Map<String, ValueType>>): List<Map<String, ValueType>> =
        keySets(patternMap.keys.toList(), row).map { keySet ->
            patternMap.filterKeys { key -> key in keySet }
        }.map { newPattern ->
            creator(newPattern)
        }.flatten()

fun <ValueType> allOrNothingCombinationIn(patternMap: Map<String, ValueType>, creator: (Map<String, ValueType>) -> List<Map<String, ValueType>>): List<Map<String, ValueType>> {
    val keyLists = if (patternMap.keys.any { isOptional(it) }) {
        listOf(patternMap.keys, patternMap.keys.filter { k -> !isOptional(k) })
    } else {
        listOf(patternMap.keys)
    }

    val keySets: List<Map<String, ValueType>> = keyLists.map { keySet ->
        patternMap.filterKeys { key -> key in keySet }
    }

    val keySetValues: List<List<Map<String, ValueType>>> = keySets.map { newPattern ->
        creator(newPattern)
    }

    val flatten: List<Map<String, ValueType>> = keySetValues.flatten()

    return flatten
}

internal fun keySets(listOfKeys: List<String>, row: Row): List<List<String>> {
    if (listOfKeys.isEmpty())
        return listOf(listOfKeys)

    val key = listOfKeys.last()
    val subLists = keySets(listOfKeys.dropLast(1), row)

    return subLists.flatMap { subList ->
        when {
            row.containsField(withoutOptionality(key)) -> listOf(subList + key)
            isOptional(key) -> listOf(subList, subList + key)
            else -> listOf(subList + key)
        }
    }
}

fun rowsToTabularPattern(rows: List<Messages.GherkinDocument.Feature.TableRow>, typeAlias: String? = null) =
        toTabularPattern(rows.map { it.cellsList }.map { (key, value) ->
            key.value to toJSONPattern(value.value)
        }.toMap(), typeAlias)

fun toJSONPattern(value: String): Pattern {
    return value.trim().let {
        val asNumber: Number? = try {
            convertToNumber(value)
        } catch (e: Throwable) {
            null
        }

        when {
            asNumber != null -> ExactValuePattern(NumberValue(asNumber))
            it.startsWith("\"") && it.endsWith("\"") ->
                ExactValuePattern(StringValue(it.removeSurrounding("\"")))
            it == "null" -> ExactValuePattern(NullValue)
            it == "true" -> ExactValuePattern(BooleanValue(true))
            it == "false" -> ExactValuePattern(BooleanValue(false))
            else -> parsedPattern(value)
        }
    }
}

fun isNumber(value: StringValue): Boolean {
    return try {
        convertToNumber(value.string)
        true
    } catch (e: ContractException) {
        false
    }
}

fun convertToNumber(value: String): Number = value.trim().let {
    stringToInt(it) ?: stringToLong(it) ?: stringToFloat(it) ?: stringToDouble(it)
    ?: throw ContractException("""Expected number, actual was "$value"""")
}

internal fun stringToInt(value: String): Int? = try {
    value.toInt()
} catch (e: Throwable) {
    null
}

internal fun stringToLong(value: String): Long? = try {
    value.toLong()
} catch (e: Throwable) {
    null
}

internal fun stringToFloat(value: String): Float? = try {
    value.toFloat()
} catch (e: Throwable) {
    null
}

internal fun stringToDouble(value: String): Double? = try {
    value.toDouble()
} catch (e: Throwable) {
    null
}
