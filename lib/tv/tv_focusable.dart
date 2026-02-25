import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lin_player_ui/lin_player_ui.dart';

class TvFocusable extends StatefulWidget {
  const TvFocusable({
    super.key,
    required this.child,
    this.onPressed,
    this.autofocus = false,
    this.focusNode,
    this.enabled = true,
    this.borderRadius = const BorderRadius.all(Radius.circular(16)),
    this.padding,
    this.surfaceColor,
    this.focusedSurfaceColor,
    this.focusScale,
  });

  final Widget child;
  final VoidCallback? onPressed;
  final bool autofocus;
  final FocusNode? focusNode;
  final bool enabled;
  final BorderRadius borderRadius;
  final EdgeInsetsGeometry? padding;
  final Color? surfaceColor;
  final Color? focusedSurfaceColor;
  final double? focusScale;

  @override
  State<TvFocusable> createState() => _TvFocusableState();
}

class _TvFocusableState extends State<TvFocusable> {
  bool _focused = false;

  void _onFocusChange(bool v) {
    if (v) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        Scrollable.ensureVisible(
          context,
          alignment: 0.5,
          duration: const Duration(milliseconds: 120),
          curve: Curves.easeOut,
        );
      });
    }
    if (_focused == v) return;
    setState(() => _focused = v);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final isDark = scheme.brightness == Brightness.dark;
    final uiScale = context.uiScale;

    final enabled = widget.enabled;
    final onPressed = enabled ? widget.onPressed : null;

    final surfaceColor = widget.surfaceColor ??
        scheme.surfaceContainerHigh.withValues(alpha: isDark ? 0.40 : 0.72);
    final focusedSurfaceColor = widget.focusedSurfaceColor ??
        scheme.primary.withValues(alpha: isDark ? 0.18 : 0.14);
    final glow = scheme.primary.withValues(alpha: isDark ? 0.55 : 0.40);

    final focusScale = widget.focusScale ?? (1.04 + (uiScale - 1.0) * 0.02);
    final effectiveScale = _focused ? focusScale : 1.0;

    return FocusableActionDetector(
      autofocus: widget.autofocus,
      focusNode: widget.focusNode,
      enabled: enabled,
      onFocusChange: _onFocusChange,
      shortcuts: const <ShortcutActivator, Intent>{
        SingleActivator(LogicalKeyboardKey.select): ActivateIntent(),
        SingleActivator(LogicalKeyboardKey.enter): ActivateIntent(),
        SingleActivator(LogicalKeyboardKey.numpadEnter): ActivateIntent(),
        SingleActivator(LogicalKeyboardKey.space): ActivateIntent(),
        SingleActivator(LogicalKeyboardKey.gameButtonA): ActivateIntent(),
        SingleActivator(LogicalKeyboardKey.accept): ActivateIntent(),
      },
      actions: <Type, Action<Intent>>{
        ActivateIntent: CallbackAction<ActivateIntent>(
          onInvoke: (_) {
            onPressed?.call();
            return null;
          },
        ),
        ButtonActivateIntent: CallbackAction<ButtonActivateIntent>(
          onInvoke: (_) {
            onPressed?.call();
            return null;
          },
        ),
      },
      child: AnimatedScale(
        scale: effectiveScale,
        duration: const Duration(milliseconds: 120),
        curve: Curves.easeOut,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 120),
          curve: Curves.easeOut,
          decoration: BoxDecoration(
            color: _focused ? focusedSurfaceColor : surfaceColor,
            borderRadius: widget.borderRadius,
            boxShadow: !_focused
                ? null
                : [
                    BoxShadow(
                      color: glow,
                      blurRadius: 18,
                      spreadRadius: 1.0,
                    ),
                    BoxShadow(
                      color: glow.withValues(alpha: glow.a * 0.55),
                      blurRadius: 44,
                      spreadRadius: 0.0,
                    ),
                  ],
          ),
          child: Material(
            color: Colors.transparent,
            shape: RoundedRectangleBorder(borderRadius: widget.borderRadius),
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              customBorder:
                  RoundedRectangleBorder(borderRadius: widget.borderRadius),
              onTap: onPressed,
              child: Padding(
                padding: widget.padding ?? EdgeInsets.zero,
                child: widget.child,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
