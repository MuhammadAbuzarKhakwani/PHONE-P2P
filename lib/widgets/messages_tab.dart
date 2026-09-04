import 'dart:async';

import 'package:flutter/material.dart';

import '../l10n/app_localizations.dart';
import '../models/gateway_models.dart';
import '../wifi_direct_service.dart';

/// Phone 2's view of the gateway's SMS.
///
/// Sending and reading are separate capabilities and are shown separately,
/// because on most devices they genuinely differ: `SEND_SMS` is a normal runtime
/// permission, while reading the inbox additionally requires the app to be the
/// phone's default SMS handler. A gateway that can send but not read is the
/// expected case, not an error, and the UI says so rather than showing an empty
/// list that looks broken.
class MessagesTab extends StatefulWidget {
  const MessagesTab({super.key, required this.service});

  final WiFiDirectService service;

  @override
  State<MessagesTab> createState() => _MessagesTabState();
}

class _MessagesTabState extends State<MessagesTab> {
  final TextEditingController _recipientController = TextEditingController();
  final TextEditingController _bodyController = TextEditingController();
  StreamSubscription<WiFiDirectEvent>? _subscription;

  GatewayStatusInfo? _status;
  List<SmsConversationInfo> _conversations = const [];
  SmsSendOutcome? _lastOutcome;
  bool _sending = false;

  @override
  void initState() {
    super.initState();
    _subscription = widget.service.eventStream.listen(_onEvent);
    _load();
  }

  @override
  void dispose() {
    _subscription?.cancel();
    _recipientController.dispose();
    _bodyController.dispose();
    super.dispose();
  }

  void _onEvent(WiFiDirectEvent event) {
    if (!mounted) return;
    if (event is GatewayStatusEvent) {
      setState(() => _status = event.status);
    } else if (event is SmsListEvent) {
      setState(() => _conversations = event.conversations);
    } else if (event is SmsOutcomeEvent) {
      setState(() {
        _lastOutcome = event.outcome;
        _sending = false;
        if (event.outcome.isSent) _bodyController.clear();
      });
    } else if (event is SessionStateChangedEvent) {
      // A different gateway may have different capabilities and different
      // messages, so nothing carries over.
      setState(() {
        _status = null;
        _conversations = const [];
        _lastOutcome = null;
      });
      _load();
    }
  }

  Future<void> _load() async {
    final status = await widget.service.getGatewayStatus();
    if (mounted) setState(() => _status = status);
    await widget.service.requestGatewayStatus();
    await widget.service.requestSmsList();
  }

  Future<void> _send() async {
    final recipient = _recipientController.text.trim();
    final body = _bodyController.text;
    if (recipient.isEmpty || body.isEmpty) return;

    setState(() {
      _sending = true;
      _lastOutcome = null;
    });
    // A refusal comes back as a failed SmsOutcomeEvent carrying the reason, so
    // the null return needs no separate handling.
    await widget.service.sendRemoteSms(recipient, body);
    if (mounted) setState(() => _sending = false);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    final status = _status;
    final canSend = status?.supports(GatewayCapability.smsSend) ?? false;
    final canRead = status?.supports(GatewayCapability.smsRead) ?? false;

    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (status == null)
            _Notice(text: l10n.messagesNoGateway, icon: Icons.link_off)
          else ...[
            _Composer(
              recipientController: _recipientController,
              bodyController: _bodyController,
              enabled: canSend && !_sending,
              sending: _sending,
              disabledReason: canSend ? null : l10n.messagesCannotSend,
              onSend: _send,
            ),
            if (_lastOutcome != null) ...[
              const SizedBox(height: 10),
              _OutcomeNotice(outcome: _lastOutcome!),
            ],
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: Text(
                    l10n.messagesTitle,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
                if (canRead)
                  TextButton.icon(
                    onPressed: _load,
                    icon: const Icon(Icons.refresh, size: 18),
                    label: Text(l10n.messagesRefresh),
                  ),
              ],
            ),
            const SizedBox(height: 8),
            if (!canRead)
              // The expected case on most devices. Explaining why beats an empty
              // list that reads as a bug.
              _Notice(text: l10n.messagesCannotRead, icon: Icons.info_outline)
            else if (_conversations.isEmpty)
              _Notice(text: l10n.messagesNone, icon: Icons.inbox_outlined)
            else
              ..._conversations.map((thread) => _ConversationTile(thread: thread)),
          ],
        ],
      ),
    );
  }
}

class _Composer extends StatelessWidget {
  const _Composer({
    required this.recipientController,
    required this.bodyController,
    required this.enabled,
    required this.sending,
    required this.disabledReason,
    required this.onSend,
  });

  final TextEditingController recipientController;
  final TextEditingController bodyController;
  final bool enabled;
  final bool sending;
  final String? disabledReason;
  final Future<void> Function() onSend;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TextField(
              controller: recipientController,
              enabled: enabled,
              keyboardType: TextInputType.phone,
              decoration: InputDecoration(
                labelText: l10n.messagesRecipient,
                border: const OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: bodyController,
              enabled: enabled,
              minLines: 2,
              maxLines: 5,
              decoration: InputDecoration(
                labelText: l10n.messagesBody,
                border: const OutlineInputBorder(),
              ),
            ),
            if (disabledReason != null) ...[
              const SizedBox(height: 8),
              Text(
                disabledReason!,
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
            const SizedBox(height: 12),
            FilledButton.icon(
              onPressed: enabled ? () => onSend() : null,
              icon: sending
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.send),
              label: Text(l10n.messagesSend),
            ),
            const SizedBox(height: 6),
            Text(
              l10n.messagesSentByGateway,
              style: Theme.of(context).textTheme.bodySmall,
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }
}

/// Reports the send outcome without overclaiming.
///
/// A successful send means the gateway handed the message to the platform.
/// Delivery reports are carrier-dependent and are not registered, so this never
/// says "delivered".
class _OutcomeNotice extends StatelessWidget {
  const _OutcomeNotice({required this.outcome});

  final SmsSendOutcome outcome;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    final theme = Theme.of(context);
    final sent = outcome.isSent;

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: sent
            ? theme.colorScheme.surfaceContainerHighest
            : theme.colorScheme.errorContainer,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(
            sent ? Icons.outbox : Icons.error_outline,
            size: 18,
            color: sent ? theme.colorScheme.onSurfaceVariant : theme.colorScheme.onErrorContainer,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              sent ? l10n.messagesHandedToNetwork : l10n.messagesSendFailed,
              style: theme.textTheme.bodySmall?.copyWith(
                color: sent ? null : theme.colorScheme.onErrorContainer,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ConversationTile extends StatelessWidget {
  const _ConversationTile({required this.thread});

  final SmsConversationInfo thread;

  String _time() {
    final at = thread.timestamp;
    final hour = at.hour.toString().padLeft(2, '0');
    return '$hour:${at.minute.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: ListTile(
        title: Text(thread.address.isEmpty ? '—' : thread.address),
        subtitle: Text(
          thread.snippet,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
        ),
        trailing: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Text(_time(), style: Theme.of(context).textTheme.bodySmall),
            if (thread.unreadCount > 0) ...[
              const SizedBox(height: 4),
              Badge(label: Text('${thread.unreadCount}')),
            ],
          ],
        ),
      ),
    );
  }
}

class _Notice extends StatelessWidget {
  const _Notice({required this.text, required this.icon});

  final String text;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 18, color: theme.colorScheme.onSurfaceVariant),
          const SizedBox(width: 10),
          Expanded(child: Text(text, style: theme.textTheme.bodySmall)),
        ],
      ),
    );
  }
}
