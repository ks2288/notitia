package dev.specter.notitia

import io.realm.kotlin.Realm
import io.realm.kotlin.RealmConfiguration
import io.realm.kotlin.exceptions.RealmException
import io.realm.kotlin.log.LogLevel
import io.realm.kotlin.log.RealmLog
import io.realm.kotlin.query.RealmQuery
import io.realm.kotlin.types.RealmObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.Integer.max
import kotlin.reflect.KClass

@Suppress("MemberVisibilityCanBePrivate", "unused")
open class RealmManager {

    //region Public Methods

    /**
     * Contains the [Set] of [RealmObject] if [defaultRealm] is set.
     */
    open lateinit var defaultSchema: Set<KClass<out RealmObject>>
        protected set

    /**
     * Defines the default [RealmConfiguration]
     */
    open lateinit var defaultRealmConfiguration: RealmConfiguration
        protected set

    /**
     * Defines the default [Realm]
     */
    open lateinit var defaultRealm: Realm
        protected set


    /**
     * Indicates if the [defaultRealm] is in on open or closed state.
     */
    open val isDefaultRealmOpen: Boolean
        get() = isInitialized && !defaultRealm.isClosed()

    /**
     * Indicates true if the [initialize] method has been called successfully.
     */
    open val isInitialized: Boolean
        get() = ::defaultRealm.isInitialized
                && ::defaultRealmConfiguration.isInitialized


    open val hasAnyRecords: Boolean
        get() = if (
            isDefaultRealmOpen &&
            ::defaultSchema.isInitialized && defaultSchema.isNotEmpty()
        ) {
            var hasAny = false
            for (it in defaultSchema) {
                if (defaultRealm.query(clazz = it).count().find() > 0L) {
                    hasAny = true
                    break
                }
            }
            println("Repo contains records: $hasAny")
            hasAny
        } else {
            false
        }


    /**
     * Used to synchronize resetting a realm
     */
    private val resetMutex = Mutex()


    init {
        println("RealmManager(`${this::class.simpleName}`) initialized.")
    }

    /**
     * Calls the suspending [resetRealm], on [coroutineScope] and invokes
     * [onReset] with the result.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    @Throws(IllegalArgumentException::class)
    open fun resetRealm(
        coroutineScope: CoroutineScope,
        realmConfiguration: RealmConfiguration? = null,
        onReset: ((Boolean, Throwable?) -> Unit)? = null
    ) {
        coroutineScope.launch {
            val (success, ex) = resetRealm(realmConfiguration)
            ex?.also { exp ->
                System.err
                    .println("Failed to reset realm with error: ${exp.message}")
                onReset?.invoke(false, exp)
            } ?: success
                .takeIf { it }
                ?.also {
                    onReset?.invoke(true, null)
                }
        }
    }

    /**
     * Removes all data from the schema specified in [realmConfiguration]; if
     * [realmConfiguration] is null, then it attemps to use
     * [defaultRealmConfiguration], and [defaultRealm] if already open
     */
    @Throws(IllegalArgumentException::class)
    open suspend fun resetRealm(
        realmConfiguration: RealmConfiguration? = null,
        schemaToReset: Set<KClass<out RealmObject>> = setOf()
    ): Pair<Boolean, Throwable?> = resetMutex.withLock {
        var isDefault = false
        val configuration = realmConfiguration
            ?: kotlin.run {
                if (::defaultRealmConfiguration.isInitialized) {
                    isDefault = true
                    defaultRealmConfiguration
                } else {
                    val ex = IllegalArgumentException(
                        "realmConfiguration is required, " +
                            "if not already initialized."
                    )
                    System.err.println(ex)
                    throw ex
                }
            }
        if (configuration.schema.isEmpty()) {
            // show the stack trace by creating an exception
            // but no throwing it, only logging it
            val ex = IllegalArgumentException(
                "realmConfiguration.schema is required to not be empty."
            )
            println(ex)

            return Pair(true, null)
        }
        val realm = if (isDefault && !defaultRealm.isClosed()) {
            println("Using the default realm to reset realm.")
            defaultRealm
        } else {
            if (isTestingEnvironmentAccessible()) {
                println("""
                        Integration/Unit testing -> Resetting realm (default: $isDefault), 
                        At path: ${configuration.path}
                        This can take several seconds, account for those in the test latch-await.
                    """.trimIndent()
                    )
            }
            Realm.open(configuration = configuration)
        }

        var totalRecordsRemoved = 0
        return try {
            val success = realm.write {
                val deletedAll: List<Boolean> = try {
                    schemaToReset.map {
                        val results = this.query(clazz = it).find()
                        if (results.isNotEmpty()) {
                            totalRecordsRemoved += results.size
                            delete(results)
                        }
                        true
                    }
                } catch (ex: Throwable) {
                    System.err.println("Failed to delete reset realm: ${ex
                        .message}")
                    listOf(false)
                }
                deletedAll.all { it }
            }
            if (!isDefault) {
                realm.close()
            } else {
                println("Successfully cleared: $totalRecordsRemoved, " +
                        "records from Realm.")
            }
            Pair(success, null)
        } catch (ex: Throwable) {
            System.err.println("Failed to reset realm: ${ex.message}")
            Pair(false, ex)
        }
    }

    /**
     * Clears realm, intended for use in testing, but can
     * be used to remove all data from the current
     * [defaultRealmConfiguration] schema items, in a blocking fashion
     */
    open fun clearRealm() {
        if (hasAnyRecords) {
            defaultRealm.writeBlocking {
                defaultSchema.forEach {
                    val results = query(clazz = it).find()
                    if (results.isNotEmpty()) {
                        println("Clearing all records of " +
                                "type: ${it::simpleName}")
                        delete(results)
                    }
                }
            }
        }
    }

    /**
     * This is called to initialize [defaultRealm], [defaultRealmConfiguration],
     * and [defaultSchema]; may be called more than once, to reinitialize with
     * a different [RealmConfiguration].
     */
    open fun initialize(
        schema: Set<KClass<out RealmObject>>,
        schemaVersion: Long = DEFAULT_SCHEMA_VERSION,
        deleteIfMigrationNeeded: Boolean = true,
        encryptionKey: ByteArray? = null,
        configuration: RealmConfiguration? = null,
        maxFileSizeMB: Int? = (DEFAULT_REALM_FILE_SIZE_MB * 1024 * 1024)
    ): Unit = synchronized(this) {
        try {
            val config = configuration ?: getConfiguration(
                schema = schema,
                schemaVersion = schemaVersion,
                deleteIfMigrationNeeded = deleteIfMigrationNeeded,
                encryptionKey = encryptionKey,
                maxFileSizeMB = maxFileSizeMB
            )
            defaultRealmConfiguration = config
            defaultRealm = Realm.open(configuration = config)
            defaultSchema = schema
            
            // TODO: link this to build type
            RealmLog.level = LogLevel.DEBUG

            println("RealmManager and default Realm initialized.")
            // derived classes should override and initialize their repos
            // by calling super.initialize(), followed by their own code
        } catch (ex: IllegalStateException) {
            // IllegalStateException thrown if the schema has changed and
            //  migration failed, or if missing an encryption key for an
            //  encrypted Realm or specified a key for unencrypted Realm.
            //  Let the caller decide how to handle.
            System.err.println("Failed to initialize " +
                    "RealmManager: ${ex.message}")
            throw ex
        } catch (ex: RealmException) {
            // Let caller decide how to handle this.
            System.err.println("Failed to initialize " +
                    "RealmManager: ${ex.message}")
            throw ex
        } catch (ex: Throwable) {
            // may include:
            // - IllegalArgumentException thrown on invalid Realm configurations
            System.err.println("Failed to initialize " +
                    "RealmManager: ${ex.message}")
        }

        if (::defaultRealmConfiguration.isInitialized.not()
            || ::defaultRealm.isInitialized.not()
            || ::defaultSchema.isInitialized.not()
        ) {
            val ex = AssertionError(
                "RealmManager initialization failed!"
            )
            System.err.println("Failed to initialize: ${ex.message}")
            throw ex
        }
    }

    /**
     * Allows a set operation of updates, for the given [clazz].
     */
    open fun <T : RealmObject> updateAll(
        clazz: KClass<T>,
        updateBlock: T.() -> Boolean
    ): Boolean = try {
        defaultRealm
            .let { realm ->
                realm
                    .query(clazz)
                    .find()
                    .map {
                        it.updateBlock()
                    }
                    .all { it }
            }
    } catch (ex: Throwable) {
        System.err.println("Failed to update Realm: ${ex.message}")
        false
    }

    /**
     * Allows [updateBlock] to update based on a [RealmQuery]
     */
    open fun <T : RealmObject> update(
        clazz: KClass<T>,
        query: RealmQuery<T>.() -> RealmQuery<T>,
        updateBlock: T.() -> Boolean
    ): Boolean = try {
        val result = defaultRealm
            .query(clazz)
            .apply { this.query() }
            .first()
        val success = result.find()
            ?.let { found ->
                found.updateBlock()
            }
            ?: false
        success
    } catch (ex: Throwable) {
        System.err.println("Failed to update realm object: ${ex.message}")
        false
    }

    //endregion

    //region Companion

    companion object {
        const val TAG: String = "REPO_MGR"
        const val DEFAULT_SCHEMA_VERSION: Long = 1L
        const val MIN_REALM_FILE_SIZE_MB: Int = 50
        const val DEFAULT_REALM_FILE_SIZE_MB: Int =
            MIN_REALM_FILE_SIZE_MB.times(2)
        const val REALM_COMPACT_ON_UNUSED_PERCENTAGE: Float = 0.5f

        @JvmStatic
        fun isTestingEnvironmentAccessible(): Boolean {
            val className = "androidx.test.espresso.Espresso"
            return try {
                Class.forName(className)
                true
            } catch (ex: ClassNotFoundException) {
                System.err.println(ex.localizedMessage)
                false
            }
        }

        /**
         * Returns a test, in-memory version of [schema] for [Realm] testing.
         */
        @JvmStatic
        fun getTestInMemoryRealm(
            schema: Set<KClass<out RealmObject>>,
            name: String = "test-realm"
        ): RealmConfiguration = RealmConfiguration
            .Builder(schema)
            .inMemory()
            .name(name)
            .build()

        /**
         * Returns a [RealmConfiguration] for use with opening [Realm] databases.
         */
        @JvmStatic
        fun getConfiguration(
            schema: Set<KClass<out RealmObject>>,
            schemaVersion: Long = DEFAULT_SCHEMA_VERSION,
            deleteIfMigrationNeeded: Boolean,
            encryptionKey: ByteArray? = null,
            maxFileSizeMB: Int? = (DEFAULT_REALM_FILE_SIZE_MB * 1024 * 1024)
        ): RealmConfiguration {
            return RealmConfiguration
                .Builder(schema)
                .schemaVersion(schemaVersion)
                .apply {
                    if (deleteIfMigrationNeeded) {
                        deleteRealmIfMigrationNeeded()
                    }
                    if (maxFileSizeMB != null) {
                        // force a min. of MIN_REALM_FILE_SIZE_MB MB
                        val maxBytes = max(
                            (MIN_REALM_FILE_SIZE_MB * 1024 * 1024),
                            maxFileSizeMB
                        )

                        compactOnLaunch { totalBytes, usedBytes ->
                            // Compact if the file is over the max file size
                            // and less than 50% used
                            val percentUsed = usedBytes
                                .toFloat()
                                .div(totalBytes.toFloat())
                            (totalBytes > maxBytes && percentUsed
                                    < REALM_COMPACT_ON_UNUSED_PERCENTAGE)
                        }
                    }
                    encryptionKey?.also { encryptionKey(it) }
                }
                .build()
        }
    }

    //endregion
}