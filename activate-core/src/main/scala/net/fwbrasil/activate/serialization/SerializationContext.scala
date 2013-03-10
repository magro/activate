package net.fwbrasil.activate.serialization

import net.fwbrasil.scala.UnsafeLazy._
import net.fwbrasil.activate.statement.StatementMocks
import net.fwbrasil.activate.entity.Entity
import net.fwbrasil.activate.entity.SerializableEntityValue
import net.fwbrasil.activate.util.ManifestUtil._

trait SerializationContext {

    protected class Serialize[E <: Entity: Manifest](columns: Set[String]) {
        def using(serializer: Serializer) = {
            var map = Map[(Class[_ <: Entity], String), Serializer]()
            for (column <- columns)
                map += (erasureOf[E], column) -> serializer
            map
        }
    }

    protected def serialize[E <: Entity: Manifest](f: (E => Unit)*) = {
        val mock = StatementMocks.mockEntity(erasureOf[E])
        f.foreach(_(mock))
        val vars = StatementMocks.fakeVarCalledStack.toSet
        val invalid = vars.filter(!_.baseTVal(None).isInstanceOf[SerializableEntityValue[_]])
        if (invalid.nonEmpty)
            throw new IllegalArgumentException(
                "Triyng to define a custom serializator for a supported property type. " +
                    "Class " + erasureOf[E].getSimpleName + " - properties: " + invalid.map(_.name).mkString(", "))
        new Serialize[E](vars.map(_.name))
    }

    private[activate] def serializatorFor(clazz: Class[_ <: Entity], property: String) =
        customSerializatorsMap.getOrElse((clazz, property), defaultSerializator)

    protected val defaultSerializator: Serializer = jsonSerializer

    private val customSerializatorsMap =
        unsafeLazy(customSerializators.flatten.toMap)

    protected def customSerializators = List[Map[(Class[_ <: Entity], String), Serializer]]()

}