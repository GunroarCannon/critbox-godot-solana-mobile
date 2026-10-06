class_name SolanaMobileMock
extends RefCounted
## A pretend wallet with the same surface and results as the Android plugin,
## so wallet flows run in the editor, on desktop and in headless tests.
## Script it: set [member next_error] to make the next request fail with that
## code ("cancelled", "declined", "no_wallet", "timeout"…), [member delay_sec]
## for the wallet's think time, [member wallet_installed] / [member device].

signal request_succeeded(id: int, json: String)
signal request_failed(id: int, code: String, message: String)

## Seconds before the pretend wallet answers.
var delay_sec := 0.25
## One-shot: the next request fails with this code, then it clears.
var next_error := ""
var wallet_installed := true
## Base58 of Keypair.fromSeed(32 × 7): a valid key, never a real wallet.
var public_key := "GmaDrppBC7P5ARKV8g3djiwP89vz1jLK23V2GBjuAEGB"
var device := {
	"manufacturer": "Godot", "model": "Mock", "brand": "mock", "sdk": 0,
	"solana_mobile": false, "seeker": false, "seed_vault": false,
}
## Every request the mock has seen, as [op, params]: for tests to inspect.
var requests_seen: Array = []

var _next_id := 1
var _busy_id := -1
var _token_serial := 0


func configure(_json: String) -> String:
	return ""


func request(op: String, params: String) -> int:
	if _busy_id != -1:
		return -1
	var parsed: Variant = JSON.parse_string(params) if params != "" else {}
	var p: Dictionary = parsed if parsed is Dictionary else {}
	_busy_id = _next_id
	_next_id += 1
	requests_seen.append([op, p])
	_answer_later(_busy_id, op, p)
	return _busy_id


func cancel() -> void:
	if _busy_id != -1:
		_fail(_busy_id, "cancelled", "Cancelled by the game")


func busy_id() -> int:
	return _busy_id


func is_wallet_installed() -> bool:
	return wallet_installed


func device_info() -> String:
	return JSON.stringify(device)


func build_transfer(params: String) -> String:
	var p: Variant = JSON.parse_string(params)
	if not (p is Dictionary) or not p.has("to") or not p.has("amount"):
		return JSON.stringify({"error": "payer, to, amount and blockhash are required"})
	# Not a real transaction: a recognisable stand-in the mock will "sign".
	return JSON.stringify({"tx": Marshalls.utf8_to_base64("mock-tx:" + JSON.stringify(p))})


func token_account(owner: String, mint: String, _token_program: String) -> String:
	return "MockAta" + (owner + mint).sha256_text().substr(0, 37)


# --- Answers ---

func _answer_later(id: int, op: String, p: Dictionary) -> void:
	var tree := Engine.get_main_loop() as SceneTree
	if tree == null or delay_sec <= 0.0:
		_answer.call_deferred(id, op, p)
		return
	tree.create_timer(delay_sec, true, false, true).timeout.connect(_answer.bind(id, op, p))


func _answer(id: int, op: String, p: Dictionary) -> void:
	if id != _busy_id:
		return
	if next_error != "":
		var code := next_error
		next_error = ""
		_fail(id, code, "Mock wallet: " + code)
		return
	if not wallet_installed:
		_fail(id, "no_wallet", "No MWA wallet installed")
		return
	var token: String = p.get("auth_token", "")
	if token == "":
		_token_serial += 1
		token = "mock-token-%d" % _token_serial
	var out := {
		"auth_token": token,
		"public_key": public_key,
		"account_label": "Mock Wallet",
		"wallet_uri_base": "",
		"accounts": [{"public_key": public_key, "label": "Mock Wallet", "chains": ["solana:devnet"], "features": []}],
	}
	match op:
		"authorize":
			if p.get("sign_in") is Dictionary:
				var si: Dictionary = p["sign_in"]
				var text := "%s wants you to sign in with your Solana account:\n%s\n\n%s" % [
						si.get("domain", ""), public_key, si.get("statement", "")]
				out["sign_in"] = {
					"public_key": public_key,
					"signed_message": Marshalls.utf8_to_base64(text),
					"signature": _fake_sig_b64(text),
					"signature_type": "ed25519",
				}
		"deauthorize":
			out = {}
		"capabilities":
			out["capabilities"] = {
				"max_transactions": 10, "max_messages": 10,
				"supported_transaction_versions": ["legacy", "0"],
				"supported_optional_features": ["solana:signInWithSolana"],
			}
		"sign_messages":
			var sigs: Array = []
			for m in p.get("messages", []):
				sigs.append(_fake_sig_b64(str(m)))
			out["signatures"] = sigs
			out["signed_messages"] = p.get("messages", [])
		"sign_transactions":
			out["signed_transactions"] = p.get("transactions", [])
		"sign_and_send":
			var sent: Array = []
			for t in p.get("transactions", []):
				sent.append("MockSig" + str(t).sha256_text())
			out["signatures"] = sent
		_:
			_fail(id, "invalid_request", "Unknown op '%s'" % op)
			return
	_busy_id = -1
	request_succeeded.emit(id, JSON.stringify(out))


func _fail(id: int, code: String, message: String) -> void:
	_busy_id = -1
	request_failed.emit(id, code, message)


func _fake_sig_b64(seed: String) -> String:
	var bytes := (seed.sha256_text() + seed.md5_text() + seed.md5_text()).to_utf8_buffer().slice(0, 64)
	return Marshalls.raw_to_base64(bytes)
