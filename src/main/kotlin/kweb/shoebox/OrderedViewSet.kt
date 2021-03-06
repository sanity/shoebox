package kweb.shoebox

import kweb.shoebox.BinarySearchResult.Between
import kweb.shoebox.BinarySearchResult.Exact
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Created by ian on 3/14/17.
 */

class OrderedViewSet<T : Any>(val view: View<T>, val viewKey: String, val comparator: Comparator<T>) {

    private val orderedList: CopyOnWriteArrayList<KeyValue<T>>
    private val modificationHandlers = ConcurrentHashMap<String, Long>()
    private val additionHandle: Long
    private val removalHandle: Long

    init {
        orderedList = CopyOnWriteArrayList<KeyValue<T>>()
        val kvComparator: Comparator<KeyValue<T>> = Comparator<KeyValue<T>> { o1, o2 -> comparator.compare(o1.value, o2.value) }.thenBy(KeyValue<T>::key)
        orderedList.addAll(view.getKeyValues(viewKey))
        orderedList.sortWith(kvComparator)
        additionHandle = view.onAdd(viewKey) { keyValue ->
            val insertionPoint: Int = when (val binarySearchResult = orderedList.betterBinarySearch(keyValue, kvComparator)) {
                is Exact -> binarySearchResult.index
                is Between -> binarySearchResult.highIndex
            }
            orderedList.add(insertionPoint, keyValue)
            insertListeners.values.forEach {
                try {
                    it(insertionPoint, keyValue)
                } catch (e: Exception) {
                    e.printStackTrace(System.err)
                }
            }
        }

        removalHandle = view.onRemove(viewKey) { keyValue ->
            if (keyValue.value != null) {
                when (val binarySearchResult = orderedList.betterBinarySearch(keyValue as KeyValue<T>, kvComparator)) {
                    is Exact -> {
                        removeListeners.values.forEach { it(binarySearchResult.index, keyValue) }
                        orderedList.removeAt(binarySearchResult.index)
                    }
                    is Between -> {
                        throw RuntimeException("$keyValue not found in orderedList $orderedList")
                    }
                }
            } else {
                // On very rare occasions the View callback doesn't supply the value that was removed, in this case
                // there isn't much we can do, so just ignore it
            }
        }

        // This isn't right, are modification handlers being added for new members of the Set?
        //
        orderedList.forEach { kv ->
            modificationHandlers[kv.key] = view.viewOf.onChange(kv.key) { oldValue, newValue, _ ->
                if (comparator.compare(oldValue, newValue) != 0) {
                    val newKeyValue = KeyValue(kv.key, newValue)
                    val insertionIndex: Int = when (val insertPoint = orderedList.betterBinarySearch(newKeyValue, kvComparator)) {
                        is Exact -> insertPoint.index
                        is Between -> insertPoint.highIndex
                    }
                    insertListeners.values.forEach { it(insertionIndex, newKeyValue) }

                    val oldKeyValue = KeyValue(kv.key, oldValue)

                    orderedList.add(insertionIndex, newKeyValue)

                    when (val removePoint = orderedList.betterBinarySearch(oldKeyValue, kvComparator)) {
                        is Exact -> {
                            orderedList.removeAt(removePoint.index)
                            removeListeners.values.forEach { it(removePoint.index, oldKeyValue) }
                        }
                    }

                for (modifyListener in modifyListeners.values) {
                    modifyListener.invoke(oldValue, newValue)
                }
            }
        }
    }
}

private val insertListeners = ConcurrentHashMap<Long, (Int, KeyValue<T>) -> Unit>()
private val removeListeners = ConcurrentHashMap<Long, (Int, KeyValue<T>) -> Unit>()
private val modifyListeners = ConcurrentHashMap<Long, (old: T, new: T) -> Unit>()

val entries: List<T> get() = keyValueEntries.map(KeyValue<T>::value)

val keyValueEntries: List<KeyValue<T>> = orderedList

fun onInsert(listener: (Int, KeyValue<T>) -> Unit): Long {
    val handle = listenerHandleSource.incrementAndGet()
    insertListeners[handle] = listener
    return handle
}

fun deleteInsertListener(handle: Long) {
    insertListeners.remove(handle)
}

fun onRemove(listener: (Int, KeyValue<T>) -> Unit): Long {
    val handle = listenerHandleSource.incrementAndGet()
    removeListeners[handle] = listener
    return handle
}

fun onModify(listener: (old: T, new: T) -> Unit): Long {
    val handle = listenerHandleSource.incrementAndGet()
    modifyListeners[handle] = listener
    return handle
}

fun deleteModifyListener(handle: Long) {
    modifyListeners.remove(handle)
}

fun deleteRemoveListener(handle: Long) {
    removeListeners.remove(handle)
}

protected fun finalize() {
    view.deleteAddListener(viewKey, additionHandle)
    view.deleteRemoveListener(viewKey, removalHandle)
    modificationHandlers.forEach { (key, handler) -> view.viewOf.deleteChangeListener(key, handler) }
}
}