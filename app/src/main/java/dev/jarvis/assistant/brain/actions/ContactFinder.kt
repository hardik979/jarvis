package dev.jarvis.assistant.brain.actions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import dev.jarvis.assistant.brain.parse.NameMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Finds the contact the user named: "Rahul", "राहुल", or "mom" for a contact saved as "Mummy". */
class ContactFinder(private val context: Context) {

    class Contact(val name: String, val number: String)

    fun canRead() = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    suspend fun find(spoken: String): Contact? = withContext(Dispatchers.IO) {
        NameMatcher.best(NameMatcher.relationVariants(spoken), allContacts()) { it.name }
    }

    /** One number per name, preferring the one marked as default. */
    private fun allContacts(): List<Contact> {
        val contacts = LinkedHashMap<String, Contact>()
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER),
            null,
            null,
            "${Phone.IS_SUPER_PRIMARY} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                val number = cursor.getString(1) ?: continue
                contacts.putIfAbsent(name, Contact(name, number))
            }
        }
        return contacts.values.toList()
    }
}
