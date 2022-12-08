/**
  * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
  */
package actorbintree

import akka.actor._
import scala.collection.immutable.Queue

object BinaryTreeSet {

  sealed trait Operation {
    def requester: ActorRef
    def id: Int
    def elem: Int
  }

  trait OperationReply {
    def id: Int
  }

  /** Request with identifier `id` to insert an element `elem` into the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Insert(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to check whether an element `elem` is present
    * in the tree. The actor at reference `requester` should be notified when
    * this operation is completed.
    */
  case class Contains(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to remove the element `elem` from the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Remove(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request to perform garbage collection */
  case object GC

  /** Holds the answer to the Contains request with identifier `id`.
    * `result` is true if and only if the element is present in the tree.
    */
  case class ContainsResult(id: Int, result: Boolean) extends OperationReply

  /** Message to signal successful completion of an insert or remove operation. */
  case class OperationFinished(id: Int) extends OperationReply

}


class BinaryTreeSet extends Actor {
  import BinaryTreeSet._
  import BinaryTreeNode._

  var id: BigInt = 0
  def createRoot: ActorRef = context.actorOf(BinaryTreeNode.props(0, initiallyRemoved = true))

  var root = createRoot

  // optional (used to stash incoming operations during garbage collection)
  var pendingQueue = Queue.empty[Operation]

  // optional
  def receive = normal

  // optional
  /** Accepts `Operation` and `GC` messages. */
  val normal: Receive = {
    case msg: Operation => root ! msg
    case _: GC.type =>
      id += 1
      val newRoot = createRoot
      root ! CopyTo(newRoot)
      context.become(garbageCollecting(newRoot))
  }


  // optional
  /** Handles messages while garbage collection is performed.
    * `newRoot` is the root of the new binary tree where we want to copy
    * all non-removed elements into.
    */
  def garbageCollecting(newRoot: ActorRef): Receive = {
    case _: GC.type     => ()
    case msg: Operation =>
      pendingQueue :+= msg
    case _: CopyFinished.type =>
      root = newRoot
      for (op <- pendingQueue) root ! op
      pendingQueue = Queue.empty[Operation]
      context.become(normal)
  }

}

object BinaryTreeNode {
  trait Position

  case object Left extends Position
  case object Right extends Position

  case class CopyTo(treeNode: ActorRef)
  /**
    * Acknowledges that a copy has been completed. This message should be sent
    * from a node to its parent, when this node and all its children nodes have
    * finished being copied.
    */
  case object CopyFinished

  def props(elem: Int, initiallyRemoved: Boolean) = Props(classOf[BinaryTreeNode],  elem, initiallyRemoved)
}

class BinaryTreeNode(val elem: Int, initiallyRemoved: Boolean) extends Actor {
  import BinaryTreeNode._
  import BinaryTreeSet._

  var subtrees = Map[Position, ActorRef]()
  var removed = initiallyRemoved

  // optional
  def receive = normal

  // optional
  /** Handles `Operation` messages and `CopyTo` requests. */
  val normal: Receive = {
    case Insert(requester, id, newElem) =>
      newElem match {
        case _ if newElem == elem =>
          removed = false
          requester ! OperationFinished(id)
        case _ if newElem < elem && subtrees.get(Left).isEmpty =>
          subtrees += Left -> createNewNode(newElem)
          requester ! OperationFinished(id)
        case _ if newElem < elem && subtrees.get(Left).nonEmpty =>
          subtrees(Left) ! Insert(requester, id, newElem)
        case _ if newElem > elem && subtrees.get(Right).isEmpty =>
          subtrees += Right -> createNewNode(newElem)
          requester ! OperationFinished(id)
        case _ if newElem > elem && subtrees.get(Right).nonEmpty =>
          subtrees(Right) ! Insert(requester, id, newElem)
      }

    case Remove(requester, id, elemToDrop) =>
      elemToDrop match {
        case _ if elemToDrop == elem =>
          removed = true
          requester ! OperationFinished(id)
        case _ if elemToDrop < elem && subtrees.get(Left).nonEmpty =>
          subtrees(Left) ! Remove(requester, id, elemToDrop)
        case _ if elemToDrop > elem && subtrees.get(Right).nonEmpty =>
          subtrees(Right) ! Remove(requester, id, elemToDrop)
        case _ => requester ! OperationFinished(id)
      }

    case Contains(requester, id, elemToFind) =>
      elemToFind match {
        case _ if elemToFind == elem && !removed =>
          requester ! ContainsResult(id, result = true)
        case _ if elemToFind == elem && removed =>
          requester ! ContainsResult(id, result = false)
        case _ if elemToFind < elem && subtrees.get(Left).nonEmpty =>
          subtrees(Left) ! Contains(requester, id, elemToFind)
        case _ if elemToFind > elem && subtrees.get(Right).nonEmpty =>
          subtrees(Right) ! Contains(requester, id, elemToFind)
        case _ => requester ! ContainsResult(id, result = false)
      }

    case CopyTo(newRoot) =>
      //      log.debug("request to copy {} in {} from {}", elem, newRoot, sender)
      if (removed && context.children.isEmpty) {
        context.parent ! CopyFinished
        self ! PoisonPill
      } else {
        if (!removed) newRoot ! Insert(self, -elem, elem)
        for (child <- context.children) child ! CopyTo(newRoot)
        context.become(copying(context.children.toSet, removed))
      }
  }
  def createNewNode(elem: Int): ActorRef =
    context.actorOf(BinaryTreeNode.props(elem, initiallyRemoved = false), s"node-$elem")

  // optional
  /** `expected` is the set of ActorRefs whose replies we are waiting for,
    * `insertConfirmed` tracks whether the copy of this node to the new tree has been confirmed.
    */
  def copying(expected: Set[ActorRef], insertConfirmed: Boolean): Receive = {
    case OperationFinished(_) =>
      //      log.debug("completed copy of {} in {}", elem, sender)
      if (expected.isEmpty) {
        context.parent ! CopyFinished
        self ! PoisonPill
      } else {
        context.become(copying(expected, insertConfirmed = true))
      }

    case _: CopyFinished.type =>
      ((expected - sender).isEmpty, insertConfirmed) match {
        case (true, true) =>
          context.parent ! CopyFinished
          self ! PoisonPill
        case (true, false) =>
          context.become(copying(Set.empty, insertConfirmed))
        case (false, _) =>
          context.become(copying(expected - sender, insertConfirmed))
      }
  }


}
