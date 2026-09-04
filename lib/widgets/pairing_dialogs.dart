import 'dart:async';

import 'dart:ui' show FontFeature;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../l10n/app_localizations.dart';
import '../wifi_direct_service.dart';

/// Owns the pairing dialogs and answers the native side's prompts.
///
/// The native pairing exchange blocks a background thread waiting for these
/// answers, so every path here must complete: each dialog resolves to a concrete
/// value, and dismissing one counts as a decision (cancel, or "not verified")
/// rather than leaving the native side to time out.
///
/// Attach once, from a widget that stays alive for the life of the app, and
/// [dispose] with it.
class PairingCoordinator {
  PairingCoordinator({
    required WiFiDirectService service,
    required BuildContext Function() contextProvider,
  }) : _service = service,
       _contextProvider = contextProvider {
    _service.pairingCodeRequestHandler = _onCodeRequested;
    _service.pairingVerifyHandler = _onVerifyRequested;
    _subscription = _service.eventStream.listen(_onEvent);
  }

  final WiFiDirectService _service;
  final BuildContext Function() _contextProvider;
  StreamSubscription<WiFiDirectEvent>? _subscription;

  /// The code dialog's own context, held so it can be dismissed by popping
  /// exactly that route. Popping the page's navigator instead risks closing
  /// whatever else happens to be on top.
  BuildContext? _codeDialogContext;

  void dispose() {
    _subscription?.cancel();
    _service.pairingCodeRequestHandler = null;
    _service.pairingVerifyHandler = null;
  }

  void _onEvent(WiFiDirectEvent event) {
    if (event is PairingCodeDisplayEvent) {
      _showCodeDialog(event.code, event.peerName);
    } else if (event is PairingFinishedEvent) {
      _dismissCodeDialog();
    }
  }

  BuildContext? get _context {
    final context = _contextProvider();
    return context.mounted ? context : null;
  }

  void _dismissCodeDialog() {
    final dialogContext = _codeDialogContext;
    _codeDialogContext = null;
    if (dialogContext != null && dialogContext.mounted) {
      Navigator.of(dialogContext).pop();
    }
  }

  /// Phone 1: shows the generated code for the user to read out.
  Future<void> _showCodeDialog(String code, String peerName) async {
    final context = _context;
    if (context == null) return;

    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) {
        _codeDialogContext = dialogContext;
        return _PairingCodeDialog(code: code, peerName: peerName);
      },
    );
    _codeDialogContext = null;
  }

  /// Phone 2: asks the user to type the code shown on Phone 1.
  Future<String?> _onCodeRequested(String peerDeviceId, String peerName) async {
    final context = _context;
    // No UI to ask with means we must fail closed. Returning null abandons the
    // pairing rather than proceeding without the user ever being asked.
    if (context == null) return null;

    return showDialog<String>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) => _PairingCodeEntryDialog(peerName: peerName),
    );
  }

  /// Both phones: confirms the short authentication string matches.
  Future<bool> _onVerifyRequested(String peerDeviceId, String sas) async {
    _dismissCodeDialog();

    final context = _context;
    if (context == null) return false;

    final confirmed = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) => _PairingVerifyDialog(shortAuthString: sas),
    );
    // Dismissed without an explicit "they match" is recorded as unverified.
    return confirmed ?? false;
  }
}

class _PairingCodeDialog extends StatelessWidget {
  const _PairingCodeDialog({required this.code, required this.peerName});

  final String code;
  final String peerName;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    return AlertDialog(
      title: Text(l10n.pairingTitle),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(l10n.pairingCodeDisplayMessage, textAlign: TextAlign.center),
          if (peerName.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(
              peerName,
              style: Theme.of(context).textTheme.bodySmall,
              textAlign: TextAlign.center,
            ),
          ],
          const SizedBox(height: 20),
          _CodeText(code: code),
          const SizedBox(height: 20),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const SizedBox(
                width: 14,
                height: 14,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
              const SizedBox(width: 10),
              Flexible(
                child: Text(
                  l10n.pairingWaitingForPeer,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _PairingCodeEntryDialog extends StatefulWidget {
  const _PairingCodeEntryDialog({required this.peerName});

  final String peerName;

  @override
  State<_PairingCodeEntryDialog> createState() => _PairingCodeEntryDialogState();
}

class _PairingCodeEntryDialogState extends State<_PairingCodeEntryDialog> {
  final TextEditingController _controller = TextEditingController();
  bool _showError = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _submit() {
    final digits = _controller.text.replaceAll(RegExp(r'\D'), '');
    if (digits.length != 6) {
      setState(() => _showError = true);
      return;
    }
    Navigator.of(context).pop(digits);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    return AlertDialog(
      title: Text(l10n.pairingEnterCodeTitle),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(l10n.pairingEnterCodeMessage),
          if (widget.peerName.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(widget.peerName, style: Theme.of(context).textTheme.bodySmall),
          ],
          const SizedBox(height: 16),
          TextField(
            controller: _controller,
            autofocus: true,
            keyboardType: TextInputType.number,
            textAlign: TextAlign.center,
            maxLength: 6,
            style: const TextStyle(fontSize: 28, letterSpacing: 8),
            inputFormatters: [
              FilteringTextInputFormatter.digitsOnly,
              LengthLimitingTextInputFormatter(6),
            ],
            decoration: InputDecoration(
              counterText: '',
              border: const OutlineInputBorder(),
              errorText: _showError ? l10n.pairingCodeInvalid : null,
            ),
            onChanged: (_) {
              if (_showError) setState(() => _showError = false);
            },
            onSubmitted: (_) => _submit(),
          ),
        ],
      ),
      actions: [
        TextButton(
          // Returning null cancels the pairing outright. It is deliberately not
          // a retry: repeated guesses would turn a six-digit code into an
          // online oracle.
          onPressed: () => Navigator.of(context).pop(),
          child: Text(l10n.pairingCancel),
        ),
        FilledButton(onPressed: _submit, child: Text(l10n.pairingConfirm)),
      ],
    );
  }
}

class _PairingVerifyDialog extends StatelessWidget {
  const _PairingVerifyDialog({required this.shortAuthString});

  final String shortAuthString;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    return AlertDialog(
      title: Text(l10n.pairingVerifyTitle),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          _CodeText(code: shortAuthString),
          const SizedBox(height: 20),
          Text(l10n.pairingVerifyMessage, textAlign: TextAlign.center),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(false),
          child: Text(l10n.pairingDoesNotMatch),
        ),
        FilledButton(
          onPressed: () => Navigator.of(context).pop(true),
          child: Text(l10n.pairingMatches),
        ),
      ],
    );
  }
}

/// Large, spaced digits — these get read aloud across a room or compared
/// between two screens, so legibility matters more than compactness.
class _CodeText extends StatelessWidget {
  const _CodeText({required this.code});

  final String code;

  @override
  Widget build(BuildContext context) {
    final grouped = code.length == 6
        ? '${code.substring(0, 3)} ${code.substring(3)}'
        : code;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Text(
        grouped,
        style: const TextStyle(
          fontSize: 34,
          fontWeight: FontWeight.w600,
          letterSpacing: 5,
          fontFeatures: [FontFeature.tabularFigures()],
        ),
      ),
    );
  }
}
