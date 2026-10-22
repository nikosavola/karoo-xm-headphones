# R8 rules for the release build.
#
# The extension reaches Karoo through the service name in the manifest, and Android keeps whatever
# the manifest names, so the app's own components need no rule here. kotlinx.serialization ships
# consumer rules for the decoders karoo-ext's inline event consumers compile into this module.

# Room 2.2.5, which comes in with karoo-ext's WorkManager, keeps the generated database class but
# not the constructor Room instantiates it through, so the app died on startup with "Failed to
# create an instance of androidx.work.impl.WorkDatabase". Newer Room ships the member in its own
# consumer rule.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
