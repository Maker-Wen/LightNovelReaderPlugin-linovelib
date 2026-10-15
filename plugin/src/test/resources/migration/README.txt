Synthetic LocalData fixtures generated with the official LightNovelReader 1.2.3b
LocalData.serializer(), Cbor and host entity serializers. They contain no user data.
tw-snapshot.zip wraps CBOR in the official ZIP data entry; tw-snapshot.cbor is the
same payload in the known raw format. tw-snapshot.json is the host Json rendering.
The fixture exercises all eleven entity lists, Float progress maps, packed Count,
JSON chapter content stored as a string, WordCount, dates and source-local values.
Some old fixture preferences/download values are intentionally only codec fixtures;
business-rule validation is covered separately by MigrationPlannerTest.
