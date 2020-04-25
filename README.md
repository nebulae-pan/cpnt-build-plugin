# cpnt-build-plugin
An Android Studio plugin for check module modification before real build

## Why dev this plugin
We are doing componentization our large Android project, but in this early phase, there is no way to completely decouple components.  
Compare to upload and deploy a AAR file when a component has been changed, We perfer use the same process to run the application.

## what this plugin does?
Use origin configuration in Android Studio, but before this confinguration execute, plugin checks the file snapshots of all modules that the current project depends on,
if snapshots doesn't match, deploy a new AAR file to repository, then we will build project with default process.
