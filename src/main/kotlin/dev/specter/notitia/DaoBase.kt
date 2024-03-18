package dev.specter.notitia

import io.realm.kotlin.Realm
import io.realm.kotlin.exceptions.RealmException
import io.realm.kotlin.notifications.InitialResults
import io.realm.kotlin.notifications.ResultsChange
import io.realm.kotlin.notifications.UpdatedResults
import io.realm.kotlin.query.RealmResults
import io.realm.kotlin.types.RealmObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Alias for [RealmException]
 */
@Suppress("unused")
typealias RepositoryException = RealmException

@Suppress("unused")
open class DaoBase(
    val realm: Realm
) {
    inline fun <reified T : RealmObject> first(): T? {
        return getAll<T>().firstOrNull()
    }

    inline fun <reified T : RealmObject> getAll(): RealmResults<T> {
        return realm.query(T::class).find()
    }

    /**
     * Creates a flow of all given types of Realm objects stored within the
     * Realm instance
     *
     * @param emitInitialValue whether the default emits initial value
     * @param flowFilter filters flow results per initial flag
     * @return flow of filtered Realm results
     */
    inline fun <reified T : RealmObject> getAllAsFlow(
        emitInitialValue: Boolean = false,
        crossinline flowFilter: (ResultsChange<T>) -> Boolean = {
            println("Flow has changed, filtering: ${it.list}")
            if (emitInitialValue) {
                it is UpdatedResults || it is InitialResults
            } else {
                it is UpdatedResults
            }
        }
    ): Flow<RealmResults<T>> {
        return realm
            .query(T::class)
            .find()
            .asFlow()
            .filter { flowFilter(it) }
            .map { it.list }
    }

    /** True if DAO for this class has been initialized */
    open val isInitialized: Boolean = true

    /** Initialize the DAO for this class */
    @Suppress("unused")
    open fun initialize(): Boolean {
        // derived classes should override this method to initialize model
        return true
    }

    override fun toString(): String {
        return "DaoBase(this=${this::class.simpleName 
            ?: "Unknown"}, isInitialized=$isInitialized)"
    }

    companion object {
        /**
         * Returns true if [values], has any non-null values, false otherwise.
         */
        @JvmStatic
        fun hasAny(vararg values: Any?): Boolean {
            for (value in values) {
                if (value != null) {
                    return true
                }
            }
            return false
        }
    }
}