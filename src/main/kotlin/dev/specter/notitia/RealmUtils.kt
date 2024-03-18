
package dev.specter.notitia

import io.reactivex.rxjava3.core.Flowable
import io.realm.kotlin.Realm
import io.realm.kotlin.RealmConfiguration
import io.realm.kotlin.ext.asFlow
import io.realm.kotlin.ext.isManaged
import io.realm.kotlin.ext.isValid
import io.realm.kotlin.internal.getRealm
import io.realm.kotlin.notifications.DeletedObject
import io.realm.kotlin.notifications.InitialObject
import io.realm.kotlin.notifications.ObjectChange
import io.realm.kotlin.notifications.UpdatedObject
import io.realm.kotlin.query.RealmResults
import io.realm.kotlin.types.BaseRealmObject
import io.realm.kotlin.types.RealmInstant
import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.TypedRealmObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.rx3.asFlowable
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

/**
 * Convenience val to convert a [RealmInstant] to a Java [Date]
 */
val RealmInstant.asDate: Date
    get() = Date(this.epochSeconds)

/**
 * Convenience val to convert a [RealmInstant] to a [LocalDateTime]
 */
val RealmInstant.asDateTime: LocalDateTime
    get() = LocalDateTime.ofInstant(
        Instant.ofEpochMilli(this.epochSeconds),
        ZoneId.systemDefault()
    )

/**
 * Converts from a [RealmInstant] to a formatted string using a Java time
 * formatter
 *
 * @param pattern formatter pattern string
 * @param tz time zone of target date
 * @return formatted date instant string
 */
fun RealmInstant.asFormattedDate(
    pattern: String? = "yyyy-MM-dd HH:mm:ss",
    tz: TimeZone? = TimeZone.getDefault()
): String = DateTimeFormatter
    .ofPattern(pattern!!)
    .withZone(tz!!.toZoneId())
    .toString()

/**
 * Convenience val to convert a [Date] instance to a [RealmInstant]
 */
val Date.asRealmInstant: RealmInstant
    get() = RealmInstant.from(this.time, 0)

/**
 * Convenience val to convert a [LocalDateTime] to a [RealmInstant]
 */
val LocalDateTime.asRealmInstant: RealmInstant
    get() = RealmInstant.from(
        this.toEpochSecond(ZoneOffset.UTC),
        0
    )

/**
 * Workaround, for [Realm]'s workaround; see documentation for [getRealm]. This
 * should only be called by an already managed [RealmObject] (i.e., one that
 * is being updated), or it will fail/throw an [AssertionError].
 *
 * #### Note this fails in unit tests, with the [ClassCastException], mentioned
 * below.
 *
 * @throws [AssertionError] unmanaged object or failure
 * @throws [ClassCastException] will occasionally fail unit test scenarios
 */
val RealmObject.defaultRealm: Realm
    get() {
        if (!this.isManaged()) {
            val ex = AssertionError(
                "Cannot get Realm from an unmanaged object."
            )
            System.err.println(ex.localizedMessage)
            throw ex
        }
        return try {
            val realm = this.getRealm<Realm>()
                ?: kotlin.run {
                    val aex = AssertionError(
                        "Failed to get a valid realm."
                    )
                    System.err.println(aex.localizedMessage)
                    throw aex
                }
            realm
        } catch (ex: Throwable) {
            System.err.println(
                "Failed to get Realm on object: ${this::class.simpleName}"
            )
            throw ex
        }
    }

/**
 * Wraps [RealmObject]'s asChangesetObservable, function, to provide a
 * [Flowable] that will filter emissions to only the [properties], specified,
 * that are the field names in the [RealmObject]; provides way to observe a
 * Realm object for all OR specified [properties], and notify when those
 * properties have changed.
 *
 * @param properties property keys as a string array; null publishes all
 * @param emitInitialValue whether it emits the initial as first value
 * @param tag execution tag
 * @return flow of changed properties within a given [RealmObject]
 */
fun <T : BaseRealmObject> RealmObject.observeChangesAsFlow(
    properties: Array<out String>? = null,
    emitInitialValue: Boolean = false,
    tag: String = "REALM_OBJECT_FLOW"
): Flow<ObjectChange<T>> {
    @Suppress("NAME_SHADOWING")
    val tag = tag.takeIf { it.isNotBlank() } ?: "REALM_OBJECT_FLOW"
    @Suppress("UNCHECKED_CAST")
    return this
        .asFlow()
        .filter { change ->
            val hasAnyPropertyChanged = when (change) {
                is UpdatedObject -> {
                    val anyFieldsChanged: Boolean = properties
                        ?.map {
                            val isChanged = change.isFieldChanged(it)
                            if (isChanged) {
                                println("$tag: Property `$it` has changed.")
                            }
                            isChanged
                        }
                        ?.any { it }
                        ?: true // all properties monitored
                    anyFieldsChanged
                }

                is DeletedObject -> {
                    println("Object has been deleted.")
                    true
                }

                is InitialObject -> {
                    emitInitialValue
                }

                else -> {
                    println("Changeset has no object: $change")
                    false
                }
            }
            if (change.obj?.isValid() == false) {
                println("Object has been deleted.")
            }
            hasAnyPropertyChanged
        } as Flow<ObjectChange<T>>
}

/**
 * Wraps [observeChangesAsFlow] to publish change sets as flows of typed
 * objects when available
 *
 * @param properties properties to watch as string array, null means all
 * @param emitInitialValue whether to emit initial as first flow value
 * @param onChangesetAvailable closure for handling published changes
 * @param tag execution/log tag
 */
fun <T : BaseRealmObject> RealmObject.asChangesetFlow(
    properties: Array<out String>? = null,
    emitInitialValue: Boolean = false,
    onChangesetAvailable: ((T?, Array<String>) -> Unit)? = null,
    tag: String = "REALM_OBJECT_FLOW"
): Flow<T?> {
    @Suppress("NAME_SHADOWING")
    val tag = tag.takeIf { it.isNotBlank() } ?: "REALM_OBJECT_FLOW"
    return observeChangesAsFlow<T>(
        properties = properties,
        emitInitialValue = emitInitialValue
    ).filter { changes ->
        val hasAnyPropertyChanged = when (changes) {
            is UpdatedObject -> {
                val anyFieldsChanged: Boolean = properties
                    ?.map {
                        val isChanged = changes.isFieldChanged(it)
                        if (isChanged) {
                            println("$tag: Property `$it` has changed.")
                        }
                        isChanged
                    }
                    ?.any { it }
                    ?: true
                onChangesetAvailable?.invoke(changes.obj, changes.changedFields)
                anyFieldsChanged
            }

            is DeletedObject -> {
                println("Object has been deleted.")
                true
            }

            is InitialObject -> {
                if (emitInitialValue) {
                    println("Emitting initial value of ${changes.obj}")
                }
                emitInitialValue
            }

            else -> {
                println("Changeset has no object: $changes")
                false
            }
        }
        if (changes.obj?.isValid() == false) {
            println("Object has been deleted.")
        }
        hasAnyPropertyChanged
    }
        .transform {
            when (it) {
                is DeletedObject -> {
                    onChangesetAvailable?.invoke(it.obj, arrayOf())
                }

                is UpdatedObject -> {
                    onChangesetAvailable?.invoke(it.obj, it.changedFields)
                }

                else -> {}
            }
            emit(it.obj)
        }
}

/**
 * Maps [asChangesetFlow] by transforming the subsequent coroutines [Flow]
 * into an Rx [Flowable]
 *
 * @param T typed Realm object proxy
 * @param properties to watch as string array, null means all
 * @param emitInitialValue whether to emit initial as first flow value
 * @param context caller's desired coroutine context
 * @param onChangesetAvailable closure for handling published changes
 * @return typed [Flowable] for published changes to a given Realm object
 */
fun <T : BaseRealmObject> RealmObject.asChangesetFlowable(
    properties: Array<out String>? = null,
    emitInitialValue: Boolean = false,
    context: CoroutineContext = Dispatchers.Default,
    onChangesetAvailable: ((T?, Array<String>) -> Unit)? = null
): Flowable<Optional<T>> = asChangesetFlow(
    properties = properties,
    emitInitialValue = emitInitialValue,
    onChangesetAvailable = onChangesetAvailable
).map { Optional.ofNullable(it) }.asFlowable(context = context)


/**
 * Deletes the object from realm
 *
 * @param T typed Realm object proxy instance
 * @param realmConfiguration Realm instance configuration
 */
fun <T : RealmObject> T.delete(realmConfiguration: RealmConfiguration) {
    val realm = Realm.open(realmConfiguration)
    try {
        realm.writeBlocking {
            delete(this@delete)
        }
    } catch (e: Throwable) {
        throw e
    } finally {
        realm.safeClose()
    }
}

fun Realm.safeClose() {
    if (this.isClosed()) {
        // do nothing
        println("dev.specter.notitia.safeClose: already closed")
    } else {
        this.close()
    }
}

/**
 * Update params of a Realm Model. It will also create the object if it doesn't
 * exist. If you have a realm managed object or need the object use [save] or
 * update.
 *
 * @param E typed Realm object
 * @param realmConfiguration Realm instance configuration
 * @param clazz Kotlin class for specified Realm model
 * @param body closure for handling the updated typed Realm object
 */
fun <E : TypedRealmObject> createOrUpdate(
    realmConfiguration: RealmConfiguration,
    clazz: KClass<E>,
    body: (E) -> Unit
) {
    var realm: Realm? = null
    try {
        realm = Realm.open(realmConfiguration)
        realm.writeBlocking {
            realm.query(clazz).first().find()?.also {
                body(it)
            }
        }
    } catch (e: IllegalStateException) {
        System.err.println(e)
    } finally {
        realm?.safeClose()
    }
}

/**
 * Deletes the object from realm
 *
 * @param T typed Realm proxy
 * @param clazz Kotlin class of type [T]
 */
fun <T : TypedRealmObject> Realm.safeDeleteWithoutClosing(clazz: KClass<T>) {
    try {
        writeBlocking {
            this.query(clazz)
                .find()
                .takeIf { it.isNotEmpty() }
                ?.also { delete(it) }
        }
    } catch (e: Throwable) {
        throw e
    }
}

/**
 * Saves the object to realm by creating a new record. For updates to existing
 * objects use [createOrUpdate]; throws if you try to save and an object with
 * the same PK
 *
 * @param T typed Realm object
 * @param realmConfiguration Realm instance configuration
 * @return the Realm-managed instance of [this]
 */
fun <T : RealmObject> T.save(realmConfiguration: RealmConfiguration): T {
    val saved: T
    val realm = Realm.open(realmConfiguration)
    try {
        saved = realm.writeBlocking {
            copyToRealm(this@save)
        }
    } catch (e: Throwable) {
        throw e
    } finally {
        realm.close()
    }
    return saved
}

/**
 * Returns all results of a given [KClass] stored in the Realm
 *
 * @param E typed Realm proxy
 * @param R escaping result
 * @param clazz Kotlin class of typed instance
 * @param configuration Realm configuration for app instance
 * @param body closure for acting on typed Realm results
 * @return typed results
 */
fun <E : TypedRealmObject, R> Realm.getAllForKClass(
    clazz: KClass<E>,
    configuration: RealmConfiguration,
    body: (RealmResults<E>) -> R
): R? {
    val realm = Realm.open(configuration)
    var output: R? = null
    try {
        realm.query(clazz)
            .find()
            .also {
                output = body.invoke(it)
            }
    } catch (e: Throwable) {
        System.err.println(e.localizedMessage)
    } finally {
        realm.close()
    }
    return output
}