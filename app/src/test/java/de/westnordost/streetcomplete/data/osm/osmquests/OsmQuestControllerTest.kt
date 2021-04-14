package de.westnordost.streetcomplete.data.osm.osmquests

import de.westnordost.countryboundaries.CountryBoundaries
import de.westnordost.osmapi.map.data.Element
import de.westnordost.osmapi.map.data.LatLon
import de.westnordost.streetcomplete.data.osm.edits.MapDataWithEditsSource
import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometryEntry
import de.westnordost.streetcomplete.data.osm.geometry.ElementPointGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.ElementKey
import de.westnordost.streetcomplete.data.osm.mapdata.MapDataWithGeometry
import de.westnordost.streetcomplete.data.osm.mapdata.MutableMapDataWithGeometry
import de.westnordost.streetcomplete.data.osmnotes.edits.NotesWithEditsSource
import de.westnordost.streetcomplete.data.quest.*
import de.westnordost.streetcomplete.ktx.containsExactlyInAnyOrder
import de.westnordost.streetcomplete.testutils.*
import de.westnordost.streetcomplete.util.enclosingBoundingBox
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.verify
import java.util.concurrent.FutureTask

class OsmQuestControllerTest {

    private lateinit var db: OsmQuestDao
    private lateinit var hiddenDB: OsmQuestsHiddenDao
    private lateinit var mapDataSource: MapDataWithEditsSource
    private lateinit var notesSource: NotesWithEditsSource
    private lateinit var questTypeRegistry: QuestTypeRegistry
    private lateinit var countryBoundaries: CountryBoundaries

    private lateinit var ctrl: OsmQuestController
    private lateinit var listener: OsmQuestSource.Listener
    private lateinit var hideListener: OsmQuestController.HideOsmQuestListener

    private lateinit var mapDataListener: MapDataWithEditsSource.Listener
    private lateinit var notesListener: NotesWithEditsSource.Listener

    @Before fun setUp() {
        db = mock()

        hiddenDB = mock()
        mapDataSource = mock()

        notesSource = mock()
        questTypeRegistry = QuestTypeRegistry(listOf(
            ApplicableQuestType, NotApplicableQuestType, ComplexQuestTypeApplicableToNode42,
            ApplicableQuestTypeNotInAnyCountry, ApplicableQuestType2
        ))
        countryBoundaries = mock()

        on(mapDataSource.addListener(any())).then { invocation ->
            mapDataListener = invocation.getArgument(0)
            Unit
        }

        on(notesSource.addListener(any())).then { invocation ->
            notesListener = invocation.getArgument(0)
            Unit
        }

        val futureTask = FutureTask { countryBoundaries }
        futureTask.run()

        listener = mock()
        hideListener = mock()
        ctrl = OsmQuestController(db, hiddenDB, mapDataSource, notesSource, questTypeRegistry, futureTask)
        ctrl.addListener(listener)
        ctrl.addHideQuestsListener(hideListener)
    }

    @Test fun getAllInBBoxCount() {
        val bbox = bbox()
        on(db.getAllInBBoxCount(bbox)).thenReturn(123)
        assertEquals(123, ctrl.getAllInBBoxCount(bbox))
    }

    @Test fun get() {
        val key = osmQuestKey(Element.Type.NODE, 1, "ApplicableQuestType")
        val entry = questEntry(Element.Type.NODE, 1, "ApplicableQuestType")
        val g = pGeom()

        on(db.get(osmQuestKey(Element.Type.NODE, 1, "ApplicableQuestType"))).thenReturn(entry)
        on(mapDataSource.getGeometry(Element.Type.NODE, 1)).thenReturn(g)

        val expectedQuest = OsmQuest(ApplicableQuestType, Element.Type.NODE, 1, g)
        assertEquals(expectedQuest, ctrl.get(key))
    }

    @Test fun getAllVisibleInBBox() {
        val notePos = p(0.5,0.5)
        val entries = listOf(
            // ok!
            questEntry(elementType = Element.Type.NODE, elementId = 1),
            // hidden!
            questEntry(elementType = Element.Type.NODE, elementId = 2),
            // blacklisted position!
            questEntry(elementType = Element.Type.NODE, elementId = 3, position = notePos),
            // geometry not found!
            questEntry(elementType = Element.Type.NODE, elementId = 4),
        )
        val geoms = listOf(ElementPointGeometry(p()))
        val hiddenQuests = listOf(OsmQuestKey(Element.Type.NODE, 2, "ApplicableQuestType"))
        val bbox = bbox()

        on(hiddenDB.getAllIds()).thenReturn(hiddenQuests)
        on(notesSource.getAllPositions(bbox)).thenReturn(listOf(notePos))
        on(db.getAllInBBox(bbox, null)).thenReturn(entries)
        on(mapDataSource.getGeometries(argThat {
            it.containsExactlyInAnyOrder(listOf(
                ElementKey(Element.Type.NODE, 1),
                ElementKey(Element.Type.NODE, 4),
            ))
        })).thenReturn(listOf(
            ElementGeometryEntry(Element.Type.NODE, 1, geoms[0])
        ))

        val expectedQuests = listOf(
            OsmQuest(ApplicableQuestType, Element.Type.NODE, 1, geoms[0]),
        )
        assertTrue(ctrl.getAllVisibleInBBox(bbox, null).containsExactlyInAnyOrder(expectedQuests))
    }


    @Test fun getAllHiddenNewerThan() {
        val geoms = listOf(
            ElementPointGeometry(p()),
            ElementPointGeometry(p()),
            ElementPointGeometry(p()),
        )

        on(hiddenDB.getNewerThan(123L)).thenReturn(listOf(
            // ok!
            OsmQuestKeyWithTimestamp(OsmQuestKey(Element.Type.NODE, 1L, "ApplicableQuestType"), 250),
            // unknown quest type
            OsmQuestKeyWithTimestamp(OsmQuestKey(Element.Type.NODE, 2L, "UnknownQuestType"), 250),
            // no geometry!
            OsmQuestKeyWithTimestamp(OsmQuestKey(Element.Type.NODE, 3L, "ApplicableQuestType"), 250),
        ))
        on(mapDataSource.getGeometries(argThat {
            it.containsExactlyInAnyOrder(listOf(
                ElementKey(Element.Type.NODE, 1),
                ElementKey(Element.Type.NODE, 2),
                ElementKey(Element.Type.NODE, 3)
            ))
        })).thenReturn(listOf(
            ElementGeometryEntry(Element.Type.NODE, 1, geoms[0]),
            ElementGeometryEntry(Element.Type.NODE, 2, geoms[1])
        ))

        assertEquals(
            listOf(
                OsmQuestHidden(Element.Type.NODE, 1, ApplicableQuestType, p(), 250)
            ),
            ctrl.getAllHiddenNewerThan(123L)
        )
    }

    @Test fun hide() {
        val quest = osmQuest(questType = ApplicableQuestType)

        on(hiddenDB.getTimestamp(eq(quest.key))).thenReturn(555)
        on(mapDataSource.getGeometry(quest.elementType, quest.elementId)).thenReturn(pGeom())

        ctrl.hide(quest.key)

        verify(hiddenDB).add(quest.key)
        verify(hideListener).onHid(eq(OsmQuestHidden(
            quest.elementType, quest.elementId, quest.osmElementQuestType, quest.position, 555
        )))
        verify(listener).onUpdated(
            addedQuests = eq(emptyList()),
            deletedQuestKeys = eq(listOf(quest.key))
        )
    }

    @Test fun unhide() {
        val quest = osmQuest(questType = ApplicableQuestType)

        on(hiddenDB.delete(quest.key)).thenReturn(true)
        on(hiddenDB.getTimestamp(eq(quest.key))).thenReturn(555)
        on(mapDataSource.getGeometry(quest.elementType, quest.elementId)).thenReturn(pGeom())
        on(db.get(quest.key)).thenReturn(quest)

        assertTrue(ctrl.unhide(quest.key))

        verify(hiddenDB).delete(quest.key)
        verify(hideListener).onUnhid(eq(OsmQuestHidden(
            quest.elementType, quest.elementId, quest.osmElementQuestType, quest.position, 555
        )))
        verify(listener).onUpdated(
            addedQuests = eq(listOf(quest)),
            deletedQuestKeys = eq(emptyList())
        )
    }

    @Test fun unhideAll() {
        on(hiddenDB.deleteAll()).thenReturn(2)
        assertEquals(2, ctrl.unhideAll())
        verify(listener).onInvalidated()
        verify(hideListener).onUnhidAll()
    }

    @Test fun `updates quests on notes listener update`() {

        val notes = listOf(note(1))

        notesListener.onUpdated(added = notes, updated = emptyList(), deleted = emptyList())

        verify(listener).onInvalidated()
    }

    @Test fun `updates quests on map data listener update for deleted elements`() {
        val quests1 = listOf(
            osmQuest(ApplicableQuestType, Element.Type.NODE, 1),
            osmQuest(ComplexQuestTypeApplicableToNode42, Element.Type.NODE, 1),
        )
        val quests2 = listOf(
            osmQuest(ApplicableQuestType, Element.Type.NODE, 2)
        )

        on(db.getAllForElement(Element.Type.NODE, 1)).thenReturn(quests1)
        on(db.getAllForElement(Element.Type.NODE, 2)).thenReturn(quests2)

        val deleted = listOf(
            ElementKey(Element.Type.NODE, 1),
            ElementKey(Element.Type.NODE, 2)
        )

        mapDataListener.onUpdated(MutableMapDataWithGeometry(), deleted)

        val expectedDeletedQuestKeys = (quests1 + quests2).map { it.key }

        verify(db).deleteAll(argThat { it.containsExactlyInAnyOrder(expectedDeletedQuestKeys) })
        verify(db).putAll(argThat { it.isEmpty() })
        verify(listener).onUpdated(
            addedQuests = eq(emptyList()),
            deletedQuestKeys = argThat { it.containsExactlyInAnyOrder(expectedDeletedQuestKeys) }
        )
    }

    @Test fun `updates quests on map data listener update for updated elements`() {

        val geom = pGeom(0.0,0.0)

        val elements = listOf(
            node(1, tags = mapOf("a" to "b")),
            // missing geometry
            node(2, tags = mapOf("a" to "b")),
        )
        val geometries = listOf(
            ElementGeometryEntry(Element.Type.NODE, 1L, geom)
        )

        val mapData = MutableMapDataWithGeometry(elements, geometries)


        val existingApplicableQuest = osmQuest(ApplicableQuestType, Element.Type.NODE, 1)
        val existingNonApplicableQuest = osmQuest(NotApplicableQuestType, Element.Type.NODE, 1)

        val previousQuests = listOf(existingApplicableQuest, existingNonApplicableQuest)

        on(db.getAllForElement(Element.Type.NODE, 1L)).thenReturn(previousQuests)
        on(mapDataSource.getMapDataWithGeometry(any())).thenReturn(mapData)

        mapDataListener.onUpdated(mapData, emptyList())

        // not testing the intricacies of createQuestsForElement because that is already covered by the
        // unhideAll tests

        val expectedCreatedQuests = listOf(
            OsmQuest(ApplicableQuestType, Element.Type.NODE, 1, geom),
            OsmQuest(ApplicableQuestType2, Element.Type.NODE, 1, geom),
        )

        val expectedDeletedQuestKeys = listOf(existingNonApplicableQuest.key)

        verify(db).deleteAll(eq(expectedDeletedQuestKeys))
        verify(db).putAll(eq(expectedCreatedQuests))
        verify(listener).onUpdated(
            addedQuests = eq(expectedCreatedQuests),
            deletedQuestKeys = eq(expectedDeletedQuestKeys)
        )

    }

    @Test fun `updates quests on map data listener replace for bbox`() {

        val elements = listOf(
            node(1),
            // missing geometry
            node(2),
            // hidden for ApplicableQuestType2
            node(3),
            // at note position
            node(4),
        )
        val geom = pGeom(0.0,0.0)
        val notePos = p(0.5,0.5)
        val notePosGeom = ElementPointGeometry(notePos)

        val geometries = listOf(
            ElementGeometryEntry(Element.Type.NODE, 1, geom),
            ElementGeometryEntry(Element.Type.NODE, 3, geom),
            ElementGeometryEntry(Element.Type.NODE, 4, ElementPointGeometry(notePos)),
        )

        val mapData = MutableMapDataWithGeometry(elements, geometries)
        val bbox = bbox()

        val existingApplicableQuest = osmQuest(ApplicableQuestType, Element.Type.NODE, 1, geom)
        val existingNonApplicableQuest = osmQuest(NotApplicableQuestType, Element.Type.NODE, 1, geom)

        val previousQuests = listOf(existingApplicableQuest, existingNonApplicableQuest)

        on(db.getAllInBBox(bbox)).thenReturn(previousQuests)

        on(notesSource.getAllPositions(notePos.enclosingBoundingBox(1.0))).thenReturn(listOf(notePos))

        on(hiddenDB.getAllIds()).thenReturn(listOf(
            OsmQuestKey(Element.Type.NODE, 3L, "ApplicableQuestType2")
        ))

        mapDataListener.onReplacedForBBox(bbox, mapData)

        val expectedAddedQuests = listOf(
            OsmQuest(ApplicableQuestType, Element.Type.NODE, 1, geom),
            OsmQuest(ApplicableQuestType2, Element.Type.NODE, 1, geom),
            OsmQuest(ApplicableQuestType, Element.Type.NODE, 3, geom),
        )

        val expectedCreatedQuests = expectedAddedQuests + listOf(
            OsmQuest(ApplicableQuestType2, Element.Type.NODE, 3, geom),
            OsmQuest(ApplicableQuestType, Element.Type.NODE, 4, notePosGeom),
            OsmQuest(ApplicableQuestType2, Element.Type.NODE, 4, notePosGeom),
        )

        val expectedDeletedQuestKeys = listOf(existingNonApplicableQuest.key)

        verify(db).deleteAll(eq(expectedDeletedQuestKeys))
        verify(db).putAll(argThat { it.containsExactlyInAnyOrder(expectedCreatedQuests) })
        verify(listener).onUpdated(
            addedQuests = argThat { it.containsExactlyInAnyOrder(expectedAddedQuests) },
            deletedQuestKeys = eq(expectedDeletedQuestKeys)
        )
    }
}

private fun questEntry(
    elementType: Element.Type = Element.Type.NODE,
    elementId: Long = 1,
    questTypeName: String = "ApplicableQuestType",
    position: LatLon = p()
): OsmQuestDaoEntry = BasicOsmQuestDaoEntry(elementType, elementId, questTypeName, position)

private object ApplicableQuestType : TestQuestTypeA() {
    override fun isApplicableTo(element: Element) = true
}

private object ApplicableQuestType2 : TestQuestTypeA() {
    override fun isApplicableTo(element: Element) = true
}

private object ApplicableQuestTypeNotInAnyCountry : TestQuestTypeA() {
    override fun isApplicableTo(element: Element) = true
    override val enabledInCountries: Countries get() = NoCountriesExcept()
}

private object NotApplicableQuestType : TestQuestTypeA() {
    override fun isApplicableTo(element: Element) = false
}

private object ComplexQuestTypeApplicableToNode42 : TestQuestTypeA() {
    override fun isApplicableTo(element: Element): Boolean? = null
    override fun getApplicableElements(mapData: MapDataWithGeometry) = listOf(node(42))
}
