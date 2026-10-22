# R8 rules for the release build.
#
# The extension reaches Karoo through the service name in the manifest, and Android keeps whatever
# the manifest names, so the app's own components need no rule here. kotlinx.serialization and Room
# ship consumer rules for what reaches them reflectively.
#
# Add a rule only for something read back by name at runtime, which today means nothing.
