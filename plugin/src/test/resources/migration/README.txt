Synthetic LocalData fixtures generated with the official LightNovelReader 1.2.3b
LocalData.serializer(), Cbor and host entity serializers. They contain no user data.
tw-snapshot.zip wraps CBOR in the official ZIP data entry; tw-snapshot.cbor is the
same payload in the known raw format. tw-snapshot.json is the host Json rendering.
The fixture exercises all eleven entity lists, Float progress maps, packed Count,
JSON chapter content stored as a string, WordCount, dates and source-local values.
Some old fixture preferences/download values are intentionally only codec fixtures;
business-rule validation is covered separately by MigrationPlannerTest.

real-illustration-components.json contains the first two image components, in their
original order and unchanged, from the real backup chapter linovelib-v3:121898
(title 插图, 21 images). No text, personal reading data or image files are included.
Source: snapshot-8-decoded.json from the 2026-10-20 original-backup regression.
Source SHA256: f12cdeb5d9f7fec92fc854445593d0e368a95715f3b7da60d6c3975ca6734ddc.
LinovelibVolumeCoverTest also reverses these two components to test the existing
first-image selection rule; that reversed order is an algorithm test input, not
another observed backup.
