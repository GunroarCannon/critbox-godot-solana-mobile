@tool
extends EditorPlugin
## Adds the Solana Mobile AAR and its Maven dependencies to Android exports.
## Needs "Use Gradle Build" on the Android export preset.

var _export_plugin: SolanaMobileExportPlugin


func _enter_tree() -> void:
	_export_plugin = SolanaMobileExportPlugin.new()
	add_export_plugin(_export_plugin)


func _exit_tree() -> void:
	remove_export_plugin(_export_plugin)
	_export_plugin = null


class SolanaMobileExportPlugin extends EditorExportPlugin:
	## Keep in step with plugin/build.gradle: the AAR does not carry its dependencies.
	const DEPENDENCIES := [
		"com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.0.8",
		"androidx.activity:activity-ktx:1.10.1",
		"org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2",
	]

	func _supports_platform(platform: EditorExportPlatform) -> bool:
		return platform is EditorExportPlatformAndroid

	func _get_android_libraries(_platform: EditorExportPlatform, debug: bool) -> PackedStringArray:
		var variant := "debug" if debug else "release"
		return PackedStringArray(["solana_mobile/bin/%s/solana_mobile-%s.aar" % [variant, variant]])

	func _get_android_dependencies(_platform: EditorExportPlatform, _debug: bool) -> PackedStringArray:
		return PackedStringArray(DEPENDENCIES)

	func _get_name() -> String:
		return "SolanaMobile"
