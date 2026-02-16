import 'package:flutter/material.dart';

import 'desktop_ui_shared.dart';

class DesktopHomePageUi extends StatelessWidget {
  const DesktopHomePageUi({super.key});

  @override
  Widget build(BuildContext context) {
    return const Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        CategorySection(),
        SizedBox(height: 40),
        ContentRowSection(title: 'Placeholder Row A'),
        SizedBox(height: 40),
        ContentRowSection(title: 'Placeholder Row B'),
        SizedBox(height: 40),
        ContentRowSection(title: 'Placeholder Row C'),
      ],
    );
  }
}

class CategorySection extends StatelessWidget {
  const CategorySection({super.key});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const UiSectionHeader(
          title: 'Placeholder Category Section',
          trailing: 'Placeholder Action',
        ),
        const SizedBox(height: 16),
        UiHorizontalScrollArea(
          children: List<Widget>.generate(
            5,
            (index) => UiCategoryCard(index: index),
          ),
        ),
      ],
    );
  }
}

class ContentRowSection extends StatelessWidget {
  const ContentRowSection({
    super.key,
    required this.title,
  });

  final String title;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        UiSectionHeader(title: title),
        const SizedBox(height: 16),
        UiHorizontalScrollArea(
          children: List<Widget>.generate(
            8,
            (index) => UiPosterCard(index: index),
          ),
        ),
      ],
    );
  }
}
