extends Control
## One button per wallet call, with a log. On Android with a wallet installed it
## talks to the real wallet on devnet; in the editor it uses the mock wallet.

const SAVE_PATH := "user://wallet_session.cfg"

var wallet: SolanaMobileClient
var _status: Label
var _log: RichTextLabel
var _mint: LineEdit
var _buttons: Array[Button] = []


func _ready() -> void:
	wallet = SolanaMobileClient.new("Solana Mobile Demo", "https://gunroarcannon.github.io", "favicon.ico", "devnet")
	wallet.account_changed.connect(func(_k: String) -> void: _refresh_status())
	_load_session()
	_build_ui()
	_refresh_status()
	var info := wallet.device_info()
	_say("Backend: %s · wallet installed: %s" % ["Android MWA" if wallet.is_native() else "mock", wallet.is_wallet_installed()])
	_say("Device: %s %s · Seeker: %s · Seed Vault: %s" % [
			info.get("manufacturer", "?"), info.get("model", "?"), info.get("seeker", false), info.get("seed_vault", false)])


# --- Actions ---

func _on_connect() -> void:
	_show(await wallet.connect_wallet(), "Connect")
	_save_session()


func _on_sign_in() -> void:
	var r := await wallet.sign_in("gunroarcannon.github.io", "Sign in to the Solana Mobile demo")
	_show(r, "Sign in")
	if r.ok and r.has("sign_in"):
		_say("  signed: " + Marshalls.base64_to_utf8(r.sign_in.signed_message).replace("\n", " ⏎ "))
	_save_session()


func _on_sign_message() -> void:
	var r := await wallet.sign_messages(["Hello from Godot %d" % Time.get_unix_time_from_system()])
	_show(r, "Sign message")
	if r.ok:
		_say("  signature: " + str(r.signatures[0]).left(24) + "…")


func _on_airdrop() -> void:
	if not wallet.is_connected_wallet():
		_say("[color=orange]Connect first[/color]")
		return
	_say("Airdrop: asking the devnet faucet…")
	var sig: Variant = await wallet.rpc("requestAirdrop", [wallet.public_key, 500_000_000])
	_say("Airdrop: " + (str(sig) if sig != null else "[color=red]faucet refused (rate limit?), use faucet.solana.com[/color]"))


func _on_balance() -> void:
	var lamports := await wallet.balance()
	_say("Balance: %s SOL" % ("?" if lamports < 0 else str(lamports / 1e9)))
	if _mint.text.strip_edges() != "":
		var tb := await wallet.token_balance(_mint.text.strip_edges())
		_say("Token balance: %s" % (str(tb.get("ui", "?")) if not tb.is_empty() else "?"))


func _on_send_sol() -> void:
	# 0.001 SOL to yourself: a real, harmless signed transfer.
	var r := await wallet.transfer(wallet.public_key, 1_000_000, "", 0, "godot-solana-mobile demo")
	_show(r, "Send 0.001 SOL")
	await _explorer(r)


func _on_send_token() -> void:
	var mint := _mint.text.strip_edges()
	if mint == "":
		_say("[color=orange]Paste a devnet token mint first[/color]")
		return
	var info: Variant = await wallet.rpc("getAccountInfo", [mint, {"encoding": "jsonParsed"}])
	if not (info is Dictionary) or info.get("value") == null:
		_say("[color=red]Mint not found on devnet[/color]")
		return
	var decimals := int(info.value.data.parsed.info.decimals)
	var program: String = info.value.owner
	var r := await wallet.transfer(wallet.public_key, int(pow(10, decimals)), mint, decimals, "demo token send", program)
	_show(r, "Send 1 token")
	await _explorer(r)


func _on_capabilities() -> void:
	var r := await wallet.capabilities()
	_show(r, "Capabilities")
	if r.ok:
		_say("  " + JSON.stringify(r.capabilities))


func _on_disconnect() -> void:
	_show(await wallet.disconnect_wallet(), "Disconnect")
	_save_session()


func _on_cancel() -> void:
	wallet.cancel()


# --- Output ---

func _show(r: Dictionary, what: String) -> void:
	if r.ok:
		_say("[color=lime]%s ok[/color] · %s" % [what, _short(wallet.public_key)])
	else:
		_say("[color=red]%s failed[/color]: %s (%s)" % [what, r.code, r.message])
	_refresh_status()


func _explorer(r: Dictionary) -> void:
	if r.ok and r.has("signatures"):
		var sig: String = r.signatures[0]
		_say("  https://explorer.solana.com/tx/%s?cluster=devnet" % sig)
		DisplayServer.clipboard_set(sig)
		# The wallet's ok only means submitted; this is the on-chain answer.
		var c := await wallet.confirm(sig)
		if c.ok:
			_say("  [color=lime]confirmed[/color] in slot %d" % c.slot)
		else:
			_say("  [color=red]not on chain[/color]: %s (%s)" % [c.code, c.message])


func _say(line: String) -> void:
	print_rich(line)
	if _log:
		_log.append_text(line + "\n")


func _short(key: String) -> String:
	return "—" if key == "" else key.left(4) + "…" + key.right(4)


func _refresh_status() -> void:
	if _status:
		_status.text = "Wallet: %s   %s" % [_short(wallet.public_key), "(busy)" if wallet.is_busy() else ""]


# --- Session ---

func _save_session() -> void:
	var cfg := ConfigFile.new()
	cfg.set_value("wallet", "auth_token", wallet.auth_token)
	cfg.set_value("wallet", "public_key", wallet.public_key)
	cfg.save(SAVE_PATH)


func _load_session() -> void:
	var cfg := ConfigFile.new()
	if cfg.load(SAVE_PATH) == OK:
		wallet.auth_token = cfg.get_value("wallet", "auth_token", "")
		wallet.public_key = cfg.get_value("wallet", "public_key", "")


# --- UI ---

func _build_ui() -> void:
	var margin := MarginContainer.new()
	margin.set_anchors_preset(Control.PRESET_FULL_RECT)
	var safe := DisplayServer.get_display_safe_area()
	for side in ["left", "right"]:
		margin.add_theme_constant_override("margin_" + side, 24)
	margin.add_theme_constant_override("margin_top", maxi(24, safe.position.y))
	margin.add_theme_constant_override("margin_bottom", 24)
	add_child(margin)
	var col := VBoxContainer.new()
	col.add_theme_constant_override("separation", 10)
	margin.add_child(col)

	var title := Label.new()
	title.text = "Godot × Solana Mobile"
	title.add_theme_font_size_override("font_size", 40)
	col.add_child(title)
	_status = Label.new()
	_status.add_theme_font_size_override("font_size", 26)
	col.add_child(_status)

	var grid := GridContainer.new()
	grid.columns = 2
	grid.add_theme_constant_override("h_separation", 10)
	grid.add_theme_constant_override("v_separation", 10)
	col.add_child(grid)
	for pair in [
		["Connect", _on_connect], ["Sign in (SIWS)", _on_sign_in],
		["Sign message", _on_sign_message], ["Capabilities", _on_capabilities],
		["Airdrop 0.5 SOL", _on_airdrop], ["Balances", _on_balance],
		["Send 0.001 SOL", _on_send_sol], ["Send 1 token", _on_send_token],
		["Disconnect", _on_disconnect], ["Cancel", _on_cancel],
	]:
		var b := Button.new()
		b.text = pair[0]
		b.custom_minimum_size = Vector2(0, 84)
		b.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		b.add_theme_font_size_override("font_size", 28)
		b.pressed.connect(pair[1])
		grid.add_child(b)
		_buttons.append(b)

	_mint = LineEdit.new()
	_mint.placeholder_text = "devnet token mint (for Send 1 token)"
	_mint.custom_minimum_size = Vector2(0, 72)
	_mint.add_theme_font_size_override("font_size", 24)
	col.add_child(_mint)

	_log = RichTextLabel.new()
	_log.bbcode_enabled = true
	_log.scroll_following = true
	_log.selection_enabled = true
	_log.size_flags_vertical = Control.SIZE_EXPAND_FILL
	_log.add_theme_font_size_override("normal_font_size", 22)
	col.add_child(_log)
