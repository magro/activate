package net.fwbrasil.activate.json.spray

import net.fwbrasil.activate.util.Reflection._
import net.fwbrasil.activate.ActivateContext
import net.fwbrasil.activate.entity.SerializableEntityValue
import net.fwbrasil.activate.entity.ListEntityValue
import net.fwbrasil.activate.entity.LazyListEntityValue
import net.fwbrasil.activate.entity.EnumerationEntityValue
import net.fwbrasil.activate.entity.DateEntityValue
import net.fwbrasil.activate.entity.FloatEntityValue
import net.fwbrasil.activate.entity.EntityInstanceEntityValue
import net.fwbrasil.activate.entity.CharEntityValue
import net.fwbrasil.activate.entity.DoubleEntityValue
import net.fwbrasil.activate.entity.LongEntityValue
import net.fwbrasil.activate.entity.StringEntityValue
import net.fwbrasil.activate.entity.BooleanEntityValue
import net.fwbrasil.activate.entity.ByteArrayEntityValue
import net.fwbrasil.activate.entity.BigDecimalEntityValue
import net.fwbrasil.activate.entity.EntityInstanceReferenceValue
import net.fwbrasil.activate.entity.CalendarEntityValue
import net.fwbrasil.activate.entity.EntityValue
import net.fwbrasil.activate.entity.Entity
import java.util.Date
import java.util.Calendar
import net.fwbrasil.activate.entity.IdVar
import net.fwbrasil.activate.util.ManifestUtil._
import net.fwbrasil.activate.entity.EntityHelper
import net.fwbrasil.activate.util.RichList._
import net.fwbrasil.activate.entity.IntEntityValue
import net.fwbrasil.activate.entity.JodaInstantEntityValue
import net.fwbrasil.activate.entity.LazyList
import org.joda.time.base.AbstractInstant
import java.text.SimpleDateFormat
import spray.json._
import java.nio.charset.Charset
import net.fwbrasil.activate.json.JsonContext
import net.fwbrasil.activate.entity.EntityInstanceEntityValue

trait SprayJsonContext extends JsonContext[JsObject] {
    val jsonCharset = Charset.defaultCharset

    private def jsValue[A](entityValue: EntityValue[A], f: A => JsValue): JsValue =
        entityValue.value.map(f(_)).getOrElse(JsNull)

    protected def jsonDateFormat =
        new SimpleDateFormat("EEE MMM dd HH:mm:ss:SSS zzz yyyy")

    private def fromJsValue(value: JsValue, entityValue: EntityValue[_]): Any =
        (value, entityValue) match {
            case (JsNull, entityValue) =>
                null
            case (JsNumber(value), entityValue: IntEntityValue) =>
                value.intValue
            case (JsNumber(value), entityValue: LongEntityValue) =>
                value.longValue
            case (JsBoolean(value), entityValue: BooleanEntityValue) =>
                value
            case (JsString(value), entityValue: CharEntityValue) =>
                value.charAt(0)
            case (JsString(value), entityValue: StringEntityValue) =>
                value
            case (JsNumber(value), entityValue: FloatEntityValue) =>
                value.floatValue
            case (JsNumber(value), entityValue: DoubleEntityValue) =>
                value.doubleValue
            case (JsNumber(value), entityValue: BigDecimalEntityValue) =>
                value
            case (JsString(value), entityValue: DateEntityValue) =>
                jsonDateFormat.parse(value)
            case (JsString(value), entityValue: JodaInstantEntityValue[_]) =>
                materializeJodaInstant(entityValue.instantClass, jsonDateFormat.parse(value))
            case (JsString(value), entityValue: CalendarEntityValue) =>
                val calendar = Calendar.getInstance
                calendar.setTime(jsonDateFormat.parse(value))
                calendar
            case (JsString(value), entityValue: ByteArrayEntityValue) =>
                value.getBytes(jsonCharset)
            case (JsString(value), entityValue: EntityInstanceEntityValue[_]) =>
                context.byId(value).getOrElse(throw new IllegalStateException("Invalid id " + value))
            case (JsString(value), entityValue: EntityInstanceReferenceValue[_]) =>
                context.byId(value).getOrElse(throw new IllegalStateException("Invalid id " + value))
            case (JsString(value), entityValue: EnumerationEntityValue[_]) =>
                val enumerationValueClass = entityValue.enumerationClass
                val enumerationClass = enumerationValueClass.getEnclosingClass
                val enumerationObjectClass = ActivateContext.loadClass(enumerationClass.getName + "$")
                val obj = getObject[Enumeration](enumerationObjectClass)
                obj.withName(value)
            case (JsArray(value), entityValue: ListEntityValue[_]) =>
                value.toList.map(fromJsValue(_, entityValue.emptyValueEntityValue))
            case (JsArray(value), entityValue: LazyListEntityValue[_]) =>
                new LazyList[Entity](value.toList.collect {
                    case JsString(id) => id
                })(entityValue.valueManifest.asInstanceOf[Manifest[Entity]])
            case (JsString(value), entityValue: SerializableEntityValue[_]) =>
                entityValue.serializator.fromSerialized(value.getBytes)(entityValue.typeManifest)
            case (obj: JsObject, entityValue: EntityInstanceEntityValue[_]) =>
                createEntityFromJson(obj)(entityValue.entityManifest)
            case (jsValue, entityValue) =>
                throw new UnsupportedOperationException(s"Can't unmarshall $jsValue to $entityValue")
        }

    private def toJsValue[T](entityValue: EntityValue[T], depth: Int, seenEntities: Set[Entity] = Set()): JsValue =
        entityValue match {
            case value: IntEntityValue =>
                jsValue[Int](value, JsNumber(_))
            case value: LongEntityValue =>
                jsValue[Long](value, JsNumber(_))
            case value: BooleanEntityValue =>
                jsValue[Boolean](value, JsBoolean(_))
            case value: CharEntityValue =>
                jsValue[Char](value, c => JsString(c.toString))
            case value: StringEntityValue =>
                jsValue[String](value, JsString(_))
            case value: FloatEntityValue =>
                jsValue[Float](value, JsNumber(_))
            case value: DoubleEntityValue =>
                jsValue[Double](value, JsNumber(_))
            case value: BigDecimalEntityValue =>
                jsValue[BigDecimal](value, JsNumber(_))
            case value: DateEntityValue =>
                jsValue[Date](value, d => JsString(jsonDateFormat.format(d)))
            case value: JodaInstantEntityValue[_] =>
                jsValue[AbstractInstant](value.asInstanceOf[JodaInstantEntityValue[AbstractInstant]], d => JsString(jsonDateFormat.format(d.toDate)))
            case value: CalendarEntityValue =>
                jsValue[Calendar](value, d => JsString(jsonDateFormat.format(d.getTime)))
            case value: ByteArrayEntityValue =>
                jsValue[Array[Byte]](value, b => JsString(new String(b)))
            case value: EntityInstanceEntityValue[_] if (depth <= 0) =>
                jsValue[Entity](value.asInstanceOf[EntityInstanceEntityValue[Entity]], e => JsString(e.id))
            case value: EntityInstanceEntityValue[_] =>
                value.asInstanceOf[EntityInstanceEntityValue[Entity]].value.map {
                    entity =>
                        _createJsonFromEntity(entity, depth - 1, seenEntities)
                }.getOrElse(JsNull)
            case value: EntityInstanceReferenceValue[_] =>
                jsValue[String](value, JsString(_))
            case value: EnumerationEntityValue[_] =>
                jsValue[Enumeration#Value](value.asInstanceOf[EnumerationEntityValue[Enumeration#Value]], e => JsString(e.toString))
            case value: ListEntityValue[_] =>
                value.value.map(list =>
                    JsArray(list.map(e => toJsValue(value.valueEntityValue(e), depth))))
                    .getOrElse(JsNull)
            case value: LazyListEntityValue[_] =>
                value.value.map(list =>
                    JsArray(list.ids.map(JsString(_))))
                    .getOrElse(JsNull)
            case value: SerializableEntityValue[_] =>
                value.value.map(v =>
                    JsString(new String(value.serializator.toSerialized(v)(value.typeManifest))))
                    .getOrElse(JsNull)
        }

    def updateEntityFromJson[E <: Entity: Manifest](jsObject: JsObject, id: String): E = {
        val entity = context.byId[E](id).getOrElse(throw new IllegalStateException("Invalid id " + id))
        updateEntityFromJson(jsObject, entity)
        entity
    }

    def updateEntityFromJson[E <: Entity: Manifest](json: String, entity: E) = {
        updateEntityFromJson(json.asJson.asJsObject, entity)
        entity
    }

    def updateEntityFromJson[E <: Entity: Manifest](jsObject: JsObject, entity: E) = {
        val fields = jsObject.fields
        val entityClass = entity.getClass
        val entityMetadata =
            EntityHelper.metadatas.find(_.entityClass == entityClass).get
        val propMetadataMap =
            entityMetadata.propertiesMetadata.mapBy(_.name)
        for ((name, jdValue) <- fields if (name != "id")) {
            val property = propMetadataMap(name)
            val ref = entity.varNamed(name)
            val entityValue = ref.toEntityPropertyValue(None)
            val value = fromJsValue(jdValue, entityValue)
            val propertyMetadata = propMetadataMap(name)
            if (propertyMetadata.isOption)
                ref.put(Option(value))
            else
                ref.putValue(value)
        }
        entity
    }

    def createEntityFromJson[E <: Entity: Manifest](json: String): E =
        createEntityFromJson[E](json.asJson.asJsObject)

    def createEntityFromJson[E <: Entity: Manifest](json: JsObject): E = {
        val entityClass = erasureOf[E]
        val id = IdVar.generateId(entityClass)
        val entity = context.liveCache.createLazyEntity(entityClass, id)
        entity.setInitialized
        entity.setNotPersisted
        updateEntityFromJson(json, entity)
        context.context.liveCache.toCache(entityClass, entity)
        entity.invariants
        entity.initializeListeners
        entity
    }

    def createOrUpdateEntityFromJson[E <: Entity: Manifest](json: String): E =
        createOrUpdateEntityFromJson[E](json.asJson.asJsObject)

    def createOrUpdateEntityFromJson[E <: Entity: Manifest](json: JsObject) = {
        json.fields.collect {
            case ("id", JsString(id)) =>
                updateEntityFromJson[E](json, id)
        }.headOption.getOrElse {
            createEntityFromJson[E](json)
        }
    }

    def updateEntityFromJson[E <: Entity: Manifest](json: String, id: String): E =
        updateEntityFromJson[E](json.asJson.asJsObject, id)

    def updateEntityFromJson[E <: Entity: Manifest](json: String): E =
        updateEntityFromJson[E](json.asJson.asJsObject)

    def updateEntityFromJson[E <: Entity: Manifest](jsValue: JsObject) = {
        jsValue.fields.collect {
            case ("id", JsString(id)) =>
                updateEntityFromJson[E](jsValue, id)
        }.headOption.getOrElse {
            throw new IllegalStateException("Can't update. The json hasn't the entity id.")
        }
    }

    def createJsonFromEntity[E <: Entity: Manifest](entity: E, depth: Int = 0): JsObject =
        _createJsonFromEntity(entity, depth, Set()).asJsObject

    private def _createJsonFromEntity[E <: Entity: Manifest](entity: E, depth: Int = 0, pSeenEntities: Set[Entity]) = {
        if (pSeenEntities.contains(entity)) 
            JsString(entity.id)
        else {
            val seenEntities = pSeenEntities ++ Set(entity)
            val fields =
                entity.vars.filter(!_.isTransient).map(ref =>
                    (ref.name, ref.get.map(value => toJsValue(ref.toEntityPropertyValue(value), depth, seenEntities)).getOrElse(JsNull))).toMap
            JsObject(fields)
        }
    }

    def createJsonStringFromEntity[E <: Entity: Manifest](entity: E, depth: Int = 0) =
        createJsonFromEntity(entity, depth).compactPrint

    implicit def entityJsonFormat[E <: Entity: Manifest] =
        new RootJsonFormat[E] {
            def write(entity: E) =
                createJsonFromEntity[E](entity)

            def read(value: JsValue) =
                createOrUpdateEntityFromJson[E](value.asJsObject)
        }

}