import 'dart:math' as math;

import 'package:flutter/material.dart';

import 'danmaku.dart';

class DanmakuStage extends StatefulWidget {
  const DanmakuStage({
    super.key,
    required this.enabled,
    required this.opacity,
    this.scale = 1.0,
    this.speed = 1.0,
    this.bold = true,
    this.maxLines = 10,
  });

  final bool enabled;
  final double opacity;
  final double scale;
  final double speed;
  final bool bold;
  final int maxLines;

  @override
  State<DanmakuStage> createState() => DanmakuStageState();
}

class DanmakuStageState extends State<DanmakuStage>
    with TickerProviderStateMixin {
  final List<_FlyingDanmaku> _active = [];
  double _width = 0;
  double _height = 0;
  int _rowCursor = 0;

  @override
  void didUpdateWidget(covariant DanmakuStage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!widget.enabled && oldWidget.enabled) {
      clear();
    }
  }

  void clear() {
    for (final a in _active) {
      a.controller.dispose();
    }
    _active.clear();
    if (mounted) setState(() {});
  }

  void emit(DanmakuItem item) {
    if (!widget.enabled) return;
    if (_width <= 0 || _height <= 0) return;

    final fontSize = 18.0 * widget.scale.clamp(0.5, 1.6);
    final fontWeight = widget.bold ? FontWeight.w600 : FontWeight.w400;
    final style = TextStyle(fontSize: fontSize, fontWeight: fontWeight);
    final painter = TextPainter(
      text: TextSpan(text: item.text, style: style),
      maxLines: 1,
      textDirection: TextDirection.ltr,
    )..layout();
    final textWidth = painter.width;

    final lineHeight = fontSize + 8;
    var rows = math.max(1, (_height / lineHeight).floor());
    rows = math.min(rows, widget.maxLines.clamp(1, 80));
    final row = _rowCursor++ % rows;
    final top = row * lineHeight + 6;

    final distance = _width + textWidth + 24;
    final speed = 140.0 * widget.speed.clamp(0.4, 2.5); // px/s
    final seconds = (distance / speed).clamp(3.0, 16.0);
    final controller = AnimationController(
        vsync: this,
        duration: Duration(milliseconds: (seconds * 1000).round()));
    final animation =
        Tween<double>(begin: _width + 12, end: -textWidth - 12).animate(
      CurvedAnimation(parent: controller, curve: Curves.linear),
    );

    final flying = _FlyingDanmaku(
      item: item,
      controller: controller,
      left: animation,
      top: top,
    );

    controller.addStatusListener((s) {
      if (s != AnimationStatus.completed) return;
      _active.remove(flying);
      controller.dispose();
      if (mounted) setState(() {});
    });

    _active.add(flying);
    controller.forward();
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    if (!widget.enabled) return const SizedBox.shrink();

    return IgnorePointer(
      child: LayoutBuilder(
        builder: (context, constraints) {
          _width = constraints.maxWidth;
          _height = constraints.maxHeight;

          return Opacity(
            opacity: widget.opacity.clamp(0, 1),
            child: Stack(
              children: [
                for (final a in List<_FlyingDanmaku>.from(_active))
                  AnimatedBuilder(
                    animation: a.left,
                    builder: (context, _) {
                      return Positioned(
                        left: a.left.value,
                        top: a.top,
                        child: _DanmakuText(
                          a.item.text,
                          fontSize: 18.0 * widget.scale.clamp(0.5, 1.6),
                          bold: widget.bold,
                        ),
                      );
                    },
                  ),
              ],
            ),
          );
        },
      ),
    );
  }
}

class _DanmakuText extends StatelessWidget {
  const _DanmakuText(
    this.text, {
    required this.fontSize,
    required this.bold,
  });

  final String text;
  final double fontSize;
  final bool bold;

  @override
  Widget build(BuildContext context) {
    return Text(
      text,
      maxLines: 1,
      overflow: TextOverflow.clip,
      style: TextStyle(
        fontSize: fontSize,
        color: Colors.white,
        fontWeight: bold ? FontWeight.w600 : FontWeight.w400,
        shadows: const [
          Shadow(
            blurRadius: 4,
            offset: Offset(1, 1),
            color: Colors.black87,
          ),
        ],
      ),
    );
  }
}

class _FlyingDanmaku {
  _FlyingDanmaku({
    required this.item,
    required this.controller,
    required this.left,
    required this.top,
  });

  final DanmakuItem item;
  final AnimationController controller;
  final Animation<double> left;
  final double top;
}
