import 'dart:async';

import 'package:flutter/material.dart';

import '../l10n/app_localizations.dart';
import '../models/gateway_models.dart';
import '../wifi_direct_service.dart';

/// Everything needed to work out why a link is or is not behaving, on whichever
/// pair of phones it is running.
///
/// This screen exists because Wi-Fi Direct behaviour varies sharply between
/// devices and cannot be exercised in CI at all, so the app has to be able to
/// explain itself on hardware. Two rules it follows throughout:
///
/// - Nothing is presented as working unless the native side actually said so. An
///   unencrypted link says it is unencrypted; an unverified pairing says so.
/// - Where Android genuinely cannot tell us something — hotspot state, for
///   instance — that is stated, rather than shown as "off".
class DiagnosticsTab extends StatefulWidget {
  const DiagnosticsTab({super.key, required this.service});

  final WiFiDirectService service;

  @override
  State<DiagnosticsTab> createState() => _DiagnosticsTabState();
}

class _DiagnosticsTabState extends State<DiagnosticsTab> {
  StreamSubscription<WiFiDirectEvent>? _subscription;

  CompatibilityReportInfo? _compatibility;
  DataSharingInfo? _dataSharing;
  Map<String, dynamic> _stats = const {};
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    // Connection state changes invalidate the stats, so refresh on them rather
    // than polling on a timer.
    _subscription = widget.service.eventStream.listen((event) {
      if (event is SessionStateChangedEvent || event is ConnectionChangedEvent) {
        _load();
      }
    });
    _load();
  }

  @override
  void dispose() {
    _subscription?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    if (!mounted) return;
    setState(() => _loading = true);

    final compatibility = await widget.service.getCompatibilityReport();
    final dataSharing = await widget.service.getDataSharingStatus();
    final stats = await widget.service.getConnectionStats();

    if (!mounted) return;
    setState(() {
      _compatibility = compatibility;
      _dataSharing = dataSharing;
      _stats = stats;
      _loading = false;
    });
  }

  bool _flag(String key) => _stats[key] == true;

  String _text(String key) => _stats[key]?.toString() ?? '';

  int _count(String key) {
    final value = _stats[key];
    if (value is int) return value;
    if (value is num) return value.toInt();
    return 0;
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;

    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  l10n.diagnosticsTitle,
                  style: Theme.of(context).textTheme.titleLarge,
                ),
              ),
              if (_loading)
                const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              else
                TextButton.icon(
                  onPressed: _load,
                  icon: const Icon(Icons.refresh, size: 18),
                  label: Text(l10n.diagnosticsRefresh),
                ),
            ],
          ),
          const SizedBox(height: 12),
          _CompatibilityCard(report: _compatibility),
          const SizedBox(height: 12),
          _SecurityCard(
            service: widget.service,
            onForgotten: _load,
            secureActive: _flag('secureActive'),
            sasVerified: _flag('secureSasVerified'),
            localDeviceId: _text('localDeviceId'),
            peerDeviceId: _text('securePeerDeviceId'),
            framesSent: _count('secureFramesSent'),
            framesReceived: _count('secureFramesReceived'),
            framesRejected: _count('secureFramesRejected'),
            duplicates: _count('secureDuplicatesDropped'),
            replays: _count('secureReplaysDropped'),
          ),
          const SizedBox(height: 12),
          _ConnectionCard(stats: _stats),
          const SizedBox(height: 12),
          _InternetCard(info: _dataSharing),
        ],
      ),
    );
  }
}

class _CompatibilityCard extends StatelessWidget {
  const _CompatibilityCard({required this.report});

  final CompatibilityReportInfo? report;

  Color _colorFor(BuildContext context, String level) {
    final scheme = Theme.of(context).colorScheme;
    switch (level) {
      case 'SUPPORTED':
        return scheme.primary;
      case 'PARTIALLY_SUPPORTED':
        return scheme.tertiary;
      default:
        return scheme.error;
    }
  }

  IconData _iconFor(String level) {
    switch (level) {
      case 'SUPPORTED':
        return Icons.check_circle_outline;
      case 'PARTIALLY_SUPPORTED':
        return Icons.error_outline;
      default:
        return Icons.cancel_outlined;
    }
  }

  String _labelFor(BuildContext context, String level) {
    final l10n = AppLocalizations.of(context)!;
    switch (level) {
      case 'SUPPORTED':
        return l10n.diagnosticsSupported;
      case 'PARTIALLY_SUPPORTED':
        return l10n.diagnosticsPartial;
      default:
        return l10n.diagnosticsUnsupported;
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    final current = report;

    return _Section(
      title: l10n.diagnosticsCompatibility,
      trailing: current == null
          ? null
          : Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  _iconFor(current.overall),
                  size: 18,
                  color: _colorFor(context, current.overall),
                ),
                const SizedBox(width: 6),
                Text(
                  _labelFor(context, current.overall),
                  style: TextStyle(color: _colorFor(context, current.overall)),
                ),
              ],
            ),
      children: current == null
          ? const []
          : current.findings.map((finding) {
              return Padding(
                padding: const EdgeInsets.symmetric(vertical: 5),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(
                      _iconFor(finding.level),
                      size: 16,
                      color: _colorFor(context, finding.level),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            finding.id,
                            style: Theme.of(context).textTheme.bodyMedium,
                          ),
                          // The native side supplies the prose, so a new reason
                          // cannot be added without an explanation to show.
                          if (finding.explanation != null)
                            Text(
                              finding.explanation!,
                              style: Theme.of(context).textTheme.bodySmall,
                            ),
                        ],
                      ),
                    ),
                  ],
                ),
              );
            }).toList(),
    );
  }
}

class _SecurityCard extends StatelessWidget {
  const _SecurityCard({
    required this.service,
    required this.onForgotten,
    required this.secureActive,
    required this.sasVerified,
    required this.localDeviceId,
    required this.peerDeviceId,
    required this.framesSent,
    required this.framesReceived,
    required this.framesRejected,
    required this.duplicates,
    required this.replays,
  });

  final WiFiDirectService service;
  final Future<void> Function() onForgotten;
  final bool secureActive;
  final bool sasVerified;
  final String localDeviceId;
  final String peerDeviceId;
  final int framesSent;
  final int framesReceived;
  final int framesRejected;
  final int duplicates;
  final int replays;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    final theme = Theme.of(context);

    return _Section(
      title: l10n.diagnosticsSecurity,
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(
              secureActive ? Icons.lock_outline : Icons.lock_open,
              size: 18,
              color: secureActive ? theme.colorScheme.primary : theme.colorScheme.error,
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                // An unencrypted link says so plainly. Showing a padlock for the
                // legacy plaintext protocol would be the single most misleading
                // thing this screen could do.
                secureActive ? l10n.diagnosticsSecureActive : l10n.diagnosticsSecureInactive,
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: secureActive ? null : theme.colorScheme.error,
                ),
              ),
            ),
          ],
        ),
        if (secureActive && !sasVerified) ...[
          const SizedBox(height: 8),
          Text(
            l10n.diagnosticsSasUnverified,
            style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.tertiary),
          ),
        ],
        const SizedBox(height: 8),
        _Row(label: l10n.diagnosticsLocalDevice, value: localDeviceId),
        if (secureActive) ...[
          _Row(label: l10n.diagnosticsPeerDevice, value: peerDeviceId),
          _Row(label: l10n.diagnosticsPacketsSent, value: '$framesSent'),
          _Row(label: l10n.diagnosticsPacketsReceived, value: '$framesReceived'),
          _Row(label: l10n.diagnosticsRejected, value: '$framesRejected'),
          _Row(label: l10n.diagnosticsDuplicates, value: '$duplicates'),
          _Row(label: l10n.diagnosticsReplays, value: '$replays'),
        ],
        const SizedBox(height: 8),
        _ForgetDevices(service: service, onForgotten: onForgotten),
      ],
    );
  }
}

/// Lets the user revoke a pairing.
///
/// Needed for two reasons, and the app was unusable without it in both:
/// revoking a lost or stolen peer, and recovering from a
/// `pairing_desynchronised` failure — where the two devices' stored secrets have
/// diverged and no code the user types can succeed until one side forgets the
/// other. Before this existed, that state could only be cleared by wiping app
/// data.
class _ForgetDevices extends StatefulWidget {
  const _ForgetDevices({required this.service, required this.onForgotten});

  final WiFiDirectService service;
  final Future<void> Function() onForgotten;

  @override
  State<_ForgetDevices> createState() => _ForgetDevicesState();
}

class _ForgetDevicesState extends State<_ForgetDevices> {
  List<Map<String, dynamic>> _devices = const [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final devices = await widget.service.getPairedDevices();
    if (mounted) setState(() => _devices = devices);
  }

  Future<void> _forget(String deviceId, String label) async {
    final l10n = AppLocalizations.of(context)!;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(l10n.pairingForgetDevice),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 12),
            Text(l10n.pairingForgetConfirm),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: Text(l10n.commonCancel),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: Text(l10n.pairingForgetDevice),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    await widget.service.forgetPairedDevice(deviceId);
    await _load();
    await widget.onForgotten();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    final theme = Theme.of(context);

    if (_devices.isEmpty) {
      return Text(l10n.pairingNoneStored, style: theme.textTheme.bodySmall);
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (final device in _devices)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 2),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        (device['name'] as String?)?.isNotEmpty == true
                            ? device['name'].toString()
                            : device['shortId']?.toString() ?? '—',
                        style: theme.textTheme.bodyMedium,
                      ),
                      // An unverified pairing is called out here too, because
                      // this is where a user would act on it.
                      if (device['sasVerified'] != true)
                        Text(
                          l10n.diagnosticsSasUnverified,
                          style: theme.textTheme.bodySmall
                              ?.copyWith(color: theme.colorScheme.tertiary),
                        ),
                    ],
                  ),
                ),
                TextButton(
                  onPressed: () => _forget(
                    device['deviceId']?.toString() ?? '',
                    (device['name'] as String?)?.isNotEmpty == true
                        ? device['name'].toString()
                        : device['shortId']?.toString() ?? '',
                  ),
                  child: Text(l10n.pairingForgetDevice),
                ),
              ],
            ),
          ),
      ],
    );
  }
}

class _ConnectionCard extends StatelessWidget {
  const _ConnectionCard({required this.stats});

  final Map<String, dynamic> stats;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;

    // Rendered generically: the native side owns this map, and listing keys here
    // would mean this screen silently omits anything added there later.
    const keys = [
      'sessionState',
      'isReady',
      'isGroupOwner',
      'transportRole',
      'groupOwnerAddress',
      'controlChannelOpen',
      'bulkChannelOpen',
      'disconnectReason',
      'lastRecoveryReason',
    ];

    return _Section(
      title: l10n.diagnosticsConnection,
      children: [
        for (final key in keys)
          if (stats.containsKey(key))
            _Row(label: key, value: stats[key]?.toString() ?? ''),
      ],
    );
  }
}

class _InternetCard extends StatelessWidget {
  const _InternetCard({required this.info});

  final DataSharingInfo? info;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    final theme = Theme.of(context);
    final current = info;

    return _Section(
      title: l10n.diagnosticsInternet,
      children: [
        // Stated first, because conflating the two is the misunderstanding this
        // whole card exists to prevent.
        Text(l10n.diagnosticsInternetSeparate, style: theme.textTheme.bodySmall),
        const SizedBox(height: 10),
        if (current == null)
          Text(l10n.diagnosticsNoInternet, style: theme.textTheme.bodyMedium)
        else ...[
          Row(
            children: [
              Icon(
                current.hasInternet ? Icons.public : Icons.public_off,
                size: 18,
                color: current.hasInternet
                    ? theme.colorScheme.primary
                    : theme.colorScheme.onSurfaceVariant,
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  current.hasInternet
                      ? (current.transport ?? '')
                      : l10n.diagnosticsNoInternet,
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Text(l10n.diagnosticsHotspotUnknown, style: theme.textTheme.bodySmall),
          const SizedBox(height: 6),
          Text(l10n.diagnosticsTetheringManual, style: theme.textTheme.bodySmall),
        ],
      ],
    );
  }
}

class _Section extends StatelessWidget {
  const _Section({required this.title, required this.children, this.trailing});

  final String title;
  final List<Widget> children;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(title, style: Theme.of(context).textTheme.titleMedium),
                ),
                if (trailing != null) trailing!,
              ],
            ),
            const SizedBox(height: 10),
            ...children,
          ],
        ),
      ),
    );
  }
}

class _Row extends StatelessWidget {
  const _Row({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final empty = value.isEmpty;

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 150,
            child: Text(label, style: theme.textTheme.bodySmall),
          ),
          Expanded(
            child: SelectableText(
              empty ? '—' : value,
              style: theme.textTheme.bodySmall?.copyWith(
                color: empty ? theme.disabledColor : null,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
