import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../l10n/app_localizations.dart';
import '../models/gateway_models.dart';
import '../wifi_direct_service.dart';

/// Phone 2's dialer: shows what the paired gateway can do, and drives it.
///
/// The screen is deliberately conservative about what it offers. Controls appear
/// only for capabilities the gateway has actually advertised, and where a
/// capability is missing the gateway's own reason is shown instead of a disabled
/// button with no explanation.
class DialerTab extends StatefulWidget {
  const DialerTab({super.key, required this.service});

  final WiFiDirectService service;

  @override
  State<DialerTab> createState() => _DialerTabState();
}

class _DialerTabState extends State<DialerTab> {
  final TextEditingController _numberController = TextEditingController();
  StreamSubscription<WiFiDirectEvent>? _subscription;
  Timer? _durationTimer;

  GatewayStatusInfo? _status;
  RemoteCallState? _call;
  DateTime? _callStartedAt;
  String? _lastFailureReason;

  @override
  void initState() {
    super.initState();
    _subscription = widget.service.eventStream.listen(_onEvent);
    _refreshStatus();
  }

  @override
  void dispose() {
    _subscription?.cancel();
    _durationTimer?.cancel();
    _numberController.dispose();
    super.dispose();
  }

  void _onEvent(WiFiDirectEvent event) {
    if (!mounted) return;

    if (event is GatewayStatusEvent) {
      setState(() => _status = event.status);
    } else if (event is RemoteCallStateEvent) {
      setState(() {
        final call = event.call;
        if (call.isTerminal) {
          _call = null;
          _callStartedAt = null;
          _durationTimer?.cancel();
          _lastFailureReason = call.isFailed ? call.reason : null;
        } else {
          _call = call;
          _lastFailureReason = null;
          _callStartedAt ??= DateTime.now();
          _startDurationTimer();
        }
      });
    } else if (event is SessionStateChangedEvent) {
      // A new session may be a different gateway with different capabilities, so
      // discard what the previous one told us rather than showing it stale.
      setState(() {
        _status = null;
        _call = null;
        _callStartedAt = null;
      });
      _refreshStatus();
    }
  }

  void _startDurationTimer() {
    _durationTimer?.cancel();
    _durationTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {});
    });
  }

  Future<void> _refreshStatus() async {
    final cached = await widget.service.getGatewayStatus();
    if (mounted && cached != null) setState(() => _status = cached);
    await widget.service.requestGatewayStatus();
  }

  Future<void> _placeCall() async {
    final number = _numberController.text.trim();
    if (number.isEmpty) return;
    // A refusal comes back as a failed RemoteCallStateEvent carrying the reason,
    // so there is nothing to do with the null return here.
    await widget.service.placeRemoteCall(number);
  }

  void _append(String digit) {
    _numberController.text += digit;
    _numberController.selection = TextSelection.collapsed(
      offset: _numberController.text.length,
    );
  }

  void _backspace() {
    final text = _numberController.text;
    if (text.isEmpty) return;
    _numberController.text = text.substring(0, text.length - 1);
    _numberController.selection = TextSelection.collapsed(
      offset: _numberController.text.length,
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    final status = _status;

    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _GatewayCard(status: status, onRefresh: _refreshStatus),
          const SizedBox(height: 12),
          if (_call != null)
            _ActiveCallCard(
              call: _call!,
              startedAt: _callStartedAt,
              onHangUp: widget.service.hangUpRemoteCall,
              onAnswer: widget.service.answerRemoteCall,
              onReject: widget.service.rejectRemoteCall,
              canAnswer: status?.supports(GatewayCapability.answer) ?? false,
            )
          else
            _DialPad(
              controller: _numberController,
              status: status,
              failureReason: _lastFailureReason,
              onDigit: _append,
              onBackspace: _backspace,
              onCall: _placeCall,
            ),
          const SizedBox(height: 16),
          _CallAudioNotice(text: l10n.gatewayCallAudioNotice),
        ],
      ),
    );
  }
}

/// The gateway's reported state. Missing values render as "Unavailable on this
/// device" rather than as a plausible default.
class _GatewayCard extends StatelessWidget {
  const _GatewayCard({required this.status, required this.onRefresh});

  final GatewayStatusInfo? status;
  final Future<void> Function() onRefresh;

  String _simLabel(BuildContext context, String simState) {
    final l10n = AppLocalizations.of(context)!;
    switch (simState) {
      case 'ready':
        return l10n.simStateReady;
      case 'absent':
        return l10n.simStateAbsent;
      case 'locked':
        return l10n.simStateLocked;
      default:
        return l10n.simStateUnknown;
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    final current = status;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    l10n.gatewayTitle,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
                TextButton.icon(
                  onPressed: () => onRefresh(),
                  icon: const Icon(Icons.refresh, size: 18),
                  label: Text(l10n.gatewayRefreshStatus),
                ),
              ],
            ),
            const SizedBox(height: 8),
            if (current == null)
              Text(
                l10n.dialerNoGateway,
                style: Theme.of(context).textTheme.bodyMedium,
              )
            else ...[
              _StatusRow(label: l10n.gatewaySim, value: _simLabel(context, current.simState)),
              _StatusRow(label: l10n.gatewayCarrier, value: current.carrier),
              _StatusRow(label: l10n.gatewayNetwork, value: current.networkType),
              _StatusRow(
                label: l10n.gatewaySignal,
                // 0 is a real reading and must be shown, so this checks for null
                // rather than falsiness.
                value: current.signalLevel == null ? null : '${current.signalLevel}/4',
              ),
              _StatusRow(label: l10n.gatewayMobileData, value: current.mobileData),
              _StatusRow(
                label: l10n.gatewayBattery,
                value: current.batteryPercent == null ? null : '${current.batteryPercent}%',
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _StatusRow extends StatelessWidget {
  const _StatusRow({required this.label, required this.value});

  final String label;

  /// Null means the platform did not expose this value.
  final String? value;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    final unavailable = value == null || value!.isEmpty;
    final theme = Theme.of(context);

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 110,
            child: Text(label, style: theme.textTheme.bodyMedium),
          ),
          Expanded(
            child: Text(
              unavailable ? l10n.gatewayUnavailableValue : value!,
              style: theme.textTheme.bodyMedium?.copyWith(
                color: unavailable ? theme.disabledColor : null,
                fontStyle: unavailable ? FontStyle.italic : null,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _DialPad extends StatelessWidget {
  const _DialPad({
    required this.controller,
    required this.status,
    required this.failureReason,
    required this.onDigit,
    required this.onBackspace,
    required this.onCall,
  });

  final TextEditingController controller;
  final GatewayStatusInfo? status;
  final String? failureReason;
  final void Function(String) onDigit;
  final VoidCallback onBackspace;
  final Future<void> Function() onCall;

  static const List<List<String>> _keys = [
    ['1', '2', '3'],
    ['4', '5', '6'],
    ['7', '8', '9'],
    ['*', '0', '#'],
  ];

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    final canDial = status?.supports(GatewayCapability.dial) ?? false;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            TextField(
              controller: controller,
              readOnly: true,
              showCursor: true,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 26, letterSpacing: 2),
              decoration: InputDecoration(
                border: const OutlineInputBorder(),
                hintText: l10n.dialerEnterNumber,
              ),
            ),
            const SizedBox(height: 12),
            for (final row in _keys)
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                children: [
                  for (final key in row)
                    Expanded(
                      child: Padding(
                        padding: const EdgeInsets.all(4),
                        child: OutlinedButton(
                          onPressed: () => onDigit(key),
                          child: Padding(
                            padding: const EdgeInsets.symmetric(vertical: 10),
                            child: Text(key, style: const TextStyle(fontSize: 20)),
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            const SizedBox(height: 8),
            if (failureReason != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text(
                  _reasonText(context, failureReason!),
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                  textAlign: TextAlign.center,
                ),
              ),
            if (!canDial)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text(
                  status == null
                      ? l10n.dialerNoGateway
                      : _reasonText(
                          context,
                          status!.reasonFor(GatewayCapability.dial) ?? '',
                        ),
                  style: Theme.of(context).textTheme.bodySmall,
                  textAlign: TextAlign.center,
                ),
              ),
            Row(
              children: [
                IconButton(
                  onPressed: onBackspace,
                  icon: const Icon(Icons.backspace_outlined),
                ),
                Expanded(
                  child: FilledButton.icon(
                    onPressed: canDial ? () => onCall() : null,
                    icon: const Icon(Icons.call),
                    label: Padding(
                      padding: const EdgeInsets.symmetric(vertical: 10),
                      child: Text(l10n.dialerCall),
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  /// Maps a machine reason from the gateway to something a person can act on.
  String _reasonText(BuildContext context, String reason) {
    final l10n = AppLocalizations.of(context)!;
    switch (reason) {
      case 'no_usable_sim':
        return l10n.simStateAbsent;
      case 'invalid_number':
        return l10n.dialerEnterNumber;
      case 'permission_not_granted':
      case 'no_telephony_hardware':
      case 'requires_privileged_or_system_app':
      case 'not_supported_on_this_android_version':
        return l10n.dialerGatewayCannotDial;
      case 'session_not_authenticated':
      case 'gateway_status_unknown':
        return l10n.dialerNoGateway;
      default:
        return l10n.dialerGatewayCannotDial;
    }
  }
}

class _ActiveCallCard extends StatelessWidget {
  const _ActiveCallCard({
    required this.call,
    required this.startedAt,
    required this.onHangUp,
    required this.onAnswer,
    required this.onReject,
    required this.canAnswer,
  });

  final RemoteCallState call;
  final DateTime? startedAt;
  final Future<bool> Function() onHangUp;
  final Future<bool> Function() onAnswer;
  final Future<bool> Function() onReject;
  final bool canAnswer;

  String _stateLabel(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    switch (call.state) {
      case 'dialing':
        return l10n.callStateDialing;
      case 'ringing':
        return l10n.callStateRinging;
      case 'active':
        return l10n.callStateActive;
      case 'ended':
        return l10n.callStateEnded;
      default:
        return l10n.callStateFailed;
    }
  }

  String _elapsed() {
    if (startedAt == null) return '00:00';
    final seconds = DateTime.now().difference(startedAt!).inSeconds;
    final minutes = (seconds ~/ 60).toString().padLeft(2, '0');
    return '$minutes:${(seconds % 60).toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          children: [
            Text(_stateLabel(context), style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            Text(_elapsed(), style: const TextStyle(fontSize: 30)),
            // Being explicit that this is not talk time. Android gives an
            // unprivileged gateway no way to see the callee answer, so claiming a
            // connected duration would be inventing information.
            if (!call.answerConfirmed) ...[
              const SizedBox(height: 4),
              Text(l10n.callSinceDialing, style: Theme.of(context).textTheme.bodySmall),
              const SizedBox(height: 6),
              Text(
                l10n.callAnswerNotConfirmed,
                style: Theme.of(context).textTheme.bodySmall,
                textAlign: TextAlign.center,
              ),
            ],
            const SizedBox(height: 20),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                if (call.isRinging && canAnswer) ...[
                  Expanded(
                    child: FilledButton.icon(
                      onPressed: () => onAnswer(),
                      icon: const Icon(Icons.call),
                      label: Text(l10n.dialerAnswer),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: OutlinedButton.icon(
                      onPressed: () => onReject(),
                      icon: const Icon(Icons.call_end),
                      label: Text(l10n.dialerReject),
                    ),
                  ),
                ] else
                  Expanded(
                    child: FilledButton.icon(
                      style: FilledButton.styleFrom(
                        backgroundColor: Theme.of(context).colorScheme.error,
                      ),
                      onPressed: () => onHangUp(),
                      icon: const Icon(Icons.call_end),
                      label: Text(l10n.dialerHangUp),
                    ),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// Always visible. The app must never leave a user expecting to hear the call on
/// this device.
class _CallAudioNotice extends StatelessWidget {
  const _CallAudioNotice({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.info_outline, size: 18, color: theme.colorScheme.onSurfaceVariant),
          const SizedBox(width: 10),
          Expanded(child: Text(text, style: theme.textTheme.bodySmall)),
        ],
      ),
    );
  }
}
