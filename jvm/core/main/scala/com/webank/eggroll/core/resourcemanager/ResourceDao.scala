package com.webank.eggroll.core.resourcemanager

import com.google.gson.Gson
import com.webank.eggroll.core.constant.SessionConfKeys.EGGROLL_SESSION_USE_RESOURCE_DISPATCH
import com.webank.eggroll.core.constant.{ProcessorStatus, SessionConfKeys, SessionStatus}
import com.webank.eggroll.core.meta._
import com.webank.eggroll.core.resourcemanager.BaseDao.NotExistError
import com.webank.eggroll.core.session.StaticErConf
import com.webank.eggroll.core.util.{JdbcTemplate, Logging}
import com.webank.eggroll.core.util.JdbcTemplate.ResultSetIterator
import org.apache.commons.lang3.StringUtils

import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class ServerMetaDao {
  private lazy val dbc = BaseDao.dbc

  def getServerCluster(clusterId: Long = 0): ErServerCluster = synchronized {
    val nodes = dbc.query(rs =>
      rs.map(_ => ErServerNode(
        id = rs.getLong("server_id"), clusterId = clusterId, name = rs.getString("name"),
        endpoint = ErEndpoint(host = rs.getString("host"), port = rs.getInt("port")),
        nodeType = rs.getString("node_type"), status = rs.getString("status")))
      , "select * from server_node where server_cluster_id=?", clusterId)
    ErServerCluster(id = clusterId, serverNodes = nodes.toArray)
  }
}

class StoreMetaDao {

}

class SessionMetaDao extends Logging{

  private  val dbc = BaseDao.dbc



  def registerWithResource(sessionMeta: ErSessionMeta, replace: Boolean = true): Unit = synchronized {
    require(sessionMeta.activeProcCount == sessionMeta.processors.count(_.status == ProcessorStatus.RUNNING),
      "conflict active proc count:" + sessionMeta)
    val sid = sessionMeta.id
    dbc.withTransaction { conn =>
      if (replace) {
        dbc.update(conn, "delete from session_main where session_id=?", sid)
        dbc.update(conn, "delete from session_option where session_id=?", sid)
        dbc.update(conn, "delete from session_processor where session_id=?", sid)
//        println(s"=================delete ${deletedProcessorsInfo.get}")
      }
      dbc.update(conn,
        "insert into session_main(session_id, name, status, tag, total_proc_count, active_proc_count) values(?, ?, ?, ?, ?, 0)",
        sid, sessionMeta.name, sessionMeta.status, sessionMeta.tag, sessionMeta.totalProcCount)
      val opts = sessionMeta.options
      if (opts.nonEmpty) {
        val valueSql = ("(?, ?, ?) ," * opts.size).stripSuffix(",")
        val params = opts.flatMap { case (k, v) => Seq(sid, k, v) }.toSeq
        dbc.update(conn, "insert into session_option(session_id, name, data) values " + valueSql, params: _*)
      }
      val procs = sessionMeta.processors
      if (procs.nonEmpty) {
        //        val valueSql = ("(?, ?, ?, ?, ?, ?, ?)," * procs.length).stripSuffix(",")
        //        val params = procs.flatMap(proc => Seq(
        //          sid, proc.serverNodeId, proc.processorType, proc.status, proc.tag,
        //          if (proc.commandEndpoint != null) proc.commandEndpoint.toString else "",
        //          if (proc.transferEndpoint != null) proc.transferEndpoint.toString else ""))
        //        dbc.update(conn,
        //          "insert into session_processor(session_id, server_node_id, processor_type, status, " +
        //            "tag, command_endpoint, transfer_endpoint) values " + valueSql,
        //          params: _*)
        procs.foreach(proc=>{
          ProcessorStateMachine.changeStatus(paramProcessor = proc.copy(sessionId = sid),preStateParam="",desStateParam=ProcessorStatus.NEW,connection = conn)
        })


      }
    }
  }

  def register(sessionMeta: ErSessionMeta, replace: Boolean = true): Unit = synchronized {
    require(sessionMeta.activeProcCount == sessionMeta.processors.count(_.status == ProcessorStatus.RUNNING),
      "conflict active proc count:" + sessionMeta)
    val sid = sessionMeta.id
    dbc.withTransaction { conn =>
      if (replace) {
        dbc.update(conn, "delete from session_main where session_id=?", sid)
        dbc.update(conn, "delete from session_option where session_id=?", sid)
        dbc.update(conn, "delete from session_processor where session_id=?", sid)
      }
      dbc.update(conn,
        "insert into session_main(session_id, name, status, tag, total_proc_count, active_proc_count) values(?, ?, ?, ?, ?, 0)",
        sid, sessionMeta.name, sessionMeta.status, sessionMeta.tag, sessionMeta.totalProcCount)
      val opts = sessionMeta.options
      if (opts.nonEmpty) {
        val valueSql = ("(?, ?, ?) ," * opts.size).stripSuffix(",")
        val params = opts.flatMap { case (k, v) => Seq(sid, k, v) }.toSeq
        dbc.update(conn, "insert into session_option(session_id, name, data) values " + valueSql, params: _*)
      }
      val procs = sessionMeta.processors
      if (procs.nonEmpty) {
        val valueSql = ("(?, ?, ?, ?, ?, ?, ?)," * procs.length).stripSuffix(",")
        val params = procs.flatMap(proc => Seq(
          sid, proc.serverNodeId, proc.processorType, proc.status, proc.tag,
          if (proc.commandEndpoint != null) proc.commandEndpoint.toString else "",
          if (proc.transferEndpoint != null) proc.transferEndpoint.toString else ""))
        dbc.update(conn,
          "insert into session_processor(session_id, server_node_id, processor_type, status, " +
            "tag, command_endpoint, transfer_endpoint) values " + valueSql,
          params: _*)

      }
    }
  }

  def registerRanks(sessionId: String, containerIds: Array[Long], nodeIds: Array[Long], localRanks: Array[Int], globalRanks: Array[Int]): Unit = {
    require(
      containerIds.length == nodeIds.length &&
        containerIds.length == globalRanks.length &&
        containerIds.length == localRanks.length)

    dbc.withTransaction { conn =>
      val valueSql = Array.fill(containerIds.length)("(?, ?, ?, ?, ?)").mkString(",")
      val params = containerIds.zip(nodeIds).zip(globalRanks).zip(localRanks).flatMap { case (((containerId, nodeId), globalRank), localRank) =>
        Seq(sessionId, containerId, nodeId, globalRank, localRank)
      }
      dbc.update(conn, "insert into session_ranks(session_id, container_id, server_node_id, global_rank, local_rank) values " + valueSql, params: _*)
    }
  }

  def getRanks(sessionId: String): Array[(Long, Long, Int, Int)] = synchronized {
    dbc.query(rs => rs.map(_ => (
      rs.getLong("container_id"),
      rs.getLong("server_node_id"),
      rs.getInt("global_rank"),
      rs.getInt("local_rank")
    )), "select * from session_ranks where session_id = ?", sessionId).toArray
  }

  def getSession(sessionId: String): ErSessionMeta = synchronized {

    val opts = dbc.query(
      rs => rs.map(
        _ => (rs.getString("name"), rs.getString("data"))
      ).toMap,
      "select * from session_option where session_id = ?", sessionId)
    val procs = dbc.query(rs => rs.map(_ => {
      var  options:java.util.Map[String,String] = new ConcurrentHashMap[String,String]()
      if (rs.getString("processor_option") != null) {
        var  gson =  new Gson()
        options.putAll(gson.fromJson(rs.getString("processor_option"),classOf[java.util.Map[String,String]]))
      }

      ErProcessor(id = rs.getLong("processor_id"),
        serverNodeId = rs.getInt("server_node_id"),
        processorType = rs.getString("processor_type"), status = rs.getString("status"),
        commandEndpoint = if (StringUtils.isBlank(rs.getString("command_endpoint"))) null
        else ErEndpoint(rs.getString("command_endpoint")),
        transferEndpoint = if (StringUtils.isBlank(rs.getString("transfer_endpoint"))) null
        else ErEndpoint(rs.getString("transfer_endpoint")),
        pid = rs.getInt("pid"),
        updatedAt = rs.getTimestamp("updated_at"),
        createdAt = rs.getTimestamp("created_at"),
        options = options
      )
    }
    ),
      "select * from session_processor where session_id = ?", sessionId)
    getSessionMain(sessionId).copy(options = opts, processors = procs.toArray)
  }

  def addSession(sessionMeta: ErSessionMeta): Unit = synchronized {
    if (dbc.queryOne("select * from session_main where session_id = ?", sessionMeta.id).nonEmpty) {
      throw new NotExistError("session exists:" + sessionMeta.id)
    }
    register(sessionMeta)
  }

  def createProcessor(conn: Connection,proc: ErProcessor): ErProcessor = synchronized{
      var  gson =  new Gson()
      val sql = "insert into session_processor " +
        "(session_id, server_node_id, processor_type, status, tag, command_endpoint, transfer_endpoint,processor_option) values " +
        "(?, ?, ?, ?, ?, ?, ?,?)"

      val result = dbc.update(conn, sql, proc.sessionId, proc.serverNodeId, proc.processorType, ProcessorStatus.NEW, proc.tag,if (proc.commandEndpoint != null) proc.commandEndpoint.toString else "",
        if (proc.transferEndpoint != null) proc.transferEndpoint.toString else "",if(proc.options!=null) gson.toJson(proc.options) else "")

      proc.copy(id=result.get.toLong)
  }


  def updateProcessor(conn: Connection, proc: ErProcessor): Unit = synchronized {
    val (session_id: String, oldStatus: String) = dbc.query(conn, rs => {
      if (!rs.next()) {
        throw new NotExistError("processor not exits:" + proc)
      }
      (rs.getString("session_id"), rs.getString("status"))
    }, "select session_id, status from session_processor where processor_id=?", proc.id)


    var sql = "update session_processor set status = ?"
    var params = List(proc.status)
    var host = dbc.query(rs => {
      if (!rs.next()) throw new NotExistError(s"host of server_node_id ${proc.serverNodeId} not exists")
      rs.getString("host")
    }, "select host from server_node where server_node_id = ?", proc.serverNodeId)

    if (!StringUtils.isBlank(proc.tag)) {
      sql += ", tag = ?"
      params ++= Array(proc.tag)
    }
    if (proc.commandEndpoint != null) {
      sql += ", command_endpoint = ?"
      params ++= Array(s"${host}:${proc.commandEndpoint.port}")
    }
    if (proc.transferEndpoint != null) {
      sql += ", transfer_endpoint = ?"
      params ++= Array(s"${host}:${proc.transferEndpoint.port}")
    }
    if (proc.pid > 0) {
      sql += ", pid = ?"
      params ++= Array(proc.pid.toString)
    }

    sql += " where processor_id = ?"
    params ++= Array(proc.id.toString)
    dbc.update(conn, sql, params: _*)
    if (oldStatus == ProcessorStatus.RUNNING && proc.status != ProcessorStatus.RUNNING) {
      dbc.update(conn, "update session_main set active_proc_count = active_proc_count - 1 where session_id = ?", session_id)
    } else if (oldStatus != ProcessorStatus.RUNNING && proc.status == ProcessorStatus.RUNNING) {
      dbc.update(conn, "update session_main set active_proc_count = active_proc_count + 1 where session_id = ?", session_id)
    }

  }

  def updateProcessor(proc: ErProcessor): Unit = synchronized {
    val (session_id: String, oldStatus: String) = dbc.query(rs => {
      if (!rs.next()) {
        throw new NotExistError("processor not exits:" + proc)
      }
      (rs.getString("session_id"), rs.getString("status"))
    }, "select session_id, status from session_processor where processor_id=?", proc.id)

    dbc.withTransaction { conn =>
      var sql = "update session_processor set status = ?"
      var params = List(proc.status)
      var host = dbc.query(rs => {
        if (!rs.next()) throw new NotExistError(s"host of server_node_id ${proc.serverNodeId} not exists")
        rs.getString("host")
      }, "select host from server_node where server_node_id = ?", proc.serverNodeId)

      if (!StringUtils.isBlank(proc.tag)) {
        sql += ", tag = ?"
        params ++= Array(proc.tag)
      }
      if (proc.commandEndpoint != null) {
        sql += ", command_endpoint = ?"
        params ++= Array(s"${host}:${proc.commandEndpoint.port}")
      }
      if (proc.transferEndpoint != null) {
        sql += ", transfer_endpoint = ?"
        params ++= Array(s"${host}:${proc.transferEndpoint.port}")
      }
      if (proc.pid > 0) {
        sql += ", pid = ?"
        params ++= Array(proc.pid.toString)
      }

      sql += " where processor_id = ?"
      params ++= Array(proc.id.toString)
      dbc.update(conn, sql, params: _*)
      if (oldStatus == ProcessorStatus.RUNNING && proc.status != ProcessorStatus.RUNNING) {
        dbc.update(conn, "update session_main set active_proc_count = active_proc_count - 1 where session_id = ?", session_id)
      } else if (oldStatus != ProcessorStatus.RUNNING && proc.status == ProcessorStatus.RUNNING) {
        dbc.update(conn, "update session_main set active_proc_count = active_proc_count + 1 where session_id = ?", session_id)
      }
    }
  }

  def getSessionMain(sessionId: String): ErSessionMeta = synchronized {
    dbc.query(rs => {
      if (!rs.next()) {
        return ErSessionMeta()
      }
      ErSessionMeta(
        id = sessionId, name = rs.getString("name"),
        totalProcCount = rs.getInt("total_proc_count"),
        activeProcCount = rs.getInt("active_proc_count"),
        status = rs.getString("status"),
        tag = rs.getString("tag"),
        createTime = rs.getTimestamp("created_at"),
        updateTime = rs.getTimestamp("updated_at"))
    }, "select * from session_main where session_id = ?", sessionId)
  }


  def getSessionMainsByStatus(status :Array[String]) : Array[ErSessionMeta] = {
    var sql = "select * from session_main where  status in "
    val whereFragments = ArrayBuffer[String]()
    val args = ArrayBuffer[String]()
    var  statusString = status.mkString("('","','","')")

    sql += statusString

    dbc.query(rs => {
      val result = ArrayBuffer[ErSessionMeta]()
      while (rs.next()) {
        result += ErSessionMeta(
          id = rs.getString("session_id"),
          name = rs.getString("name"),
          totalProcCount = rs.getInt("total_proc_count"),
          activeProcCount = rs.getInt("active_proc_count"),
          status = rs.getString("status"),
          createTime= rs.getTimestamp("created_at"),
          tag = rs.getString("tag"))
      }
      result.toArray
    }, sql, args: _*)
  }

  def getSessionMains(sessionMeta: ErSessionMeta): Array[ErSessionMeta] = synchronized {
    var sql = "select * from session_main where "
    val whereFragments = ArrayBuffer[String]()
    val args = ArrayBuffer[String]()
    if (!StringUtils.isBlank(sessionMeta.status)) {
      whereFragments += "status = ?"
      args += sessionMeta.status
    }

    if (!StringUtils.isBlank(sessionMeta.tag)) {
      whereFragments += "tag = ?"
      args += sessionMeta.tag
    }

    sql += String.join(" and ", whereFragments: _*)

    dbc.query(rs => {
      val result = ArrayBuffer[ErSessionMeta]()
      while (rs.next()) {
        result += ErSessionMeta(
          id = rs.getString("session_id"),
          name = rs.getString("name"),
          totalProcCount = rs.getInt("total_proc_count"),
          activeProcCount = rs.getInt("active_proc_count"),
          status = rs.getString("status"),
          createTime= rs.getTimestamp("created_at"),
          tag = rs.getString("tag"))
      }
      result.toArray
    }, sql, args: _*)
  }

  def getStoreLocators(input: ErStore): ErStoreList = synchronized {
    var sql = "select * from store_locator where status = 'NORMAL' and"
    val whereFragments = ArrayBuffer[String]()
    val args = ArrayBuffer[String]()

    val store_locator = input.storeLocator
    val store_name = store_locator.name
    val store_namespace = store_locator.namespace
    val store_type = store_locator.storeType
    var store_name_new = ""
    if (!StringUtils.isBlank(store_name)) {
      if (StringUtils.contains(store_name, "*")) {
        store_name_new = store_name.replace('*', '%')
        args += store_name_new
      } else {
        args += store_name
      }
      whereFragments += " name like ?"
    }

    if (!StringUtils.isBlank(store_namespace)) {
      whereFragments += " namespace = ?"
      args += store_namespace
    }

    sql += String.join(" and ", whereFragments: _*)

    dbc.query(rs => {
      val stores = ArrayBuffer[ErStore]()
      while (rs.next()) {

        stores += ErStore(
          storeLocator = ErStoreLocator(
            storeType = rs.getString("store_type"),
            name = rs.getString("name"),
            namespace = rs.getString("namespace"),
            totalPartitions = rs.getInt("total_partitions")
          ))
      }

      ErStoreList(stores = stores.toArray)
    }, sql, args: _*)

  }

  def existSession(sessionId: String): Boolean = synchronized {
    dbc.queryOne("select 1 from session_main where session_id = ?", sessionId).nonEmpty
  }

  def updateSessionMain(sessionMeta: ErSessionMeta ,afterCall:(Connection,ErSessionMeta)=>Unit=null): Unit = synchronized {
    dbc.withTransaction { conn =>
      dbc.update(conn, "update session_main set name = ? , status = ? , tag = ? , active_proc_count = ? where session_id = ?",
        sessionMeta.name, sessionMeta.status, sessionMeta.tag, sessionMeta.activeProcCount, sessionMeta.id)
      if(afterCall!=null){
        afterCall(conn,sessionMeta)
      }
    }
  }

  def updateSessionStatus(sessionId: String, status: String, required_old_status: Option[String] = None): Unit = synchronized {
    required_old_status match {
      case Some(old_status) =>
        dbc.withTransaction { conn =>
          dbc.update(conn, "update session_main set status =?  where session_id = ? and status = ?",
            status, sessionId, old_status)
        }
      case None =>
        dbc.withTransaction { conn =>
          dbc.update(conn, "update session_main set status =?  where session_id = ?",
            status, sessionId)
        }
    }
  }

  // status and tag only
  def batchUpdateSessionProcessor(sessionMeta: ErSessionMeta): Unit = synchronized {
    dbc.withTransaction { conn =>
      if (StringUtils.isBlank(sessionMeta.id)) throw new IllegalArgumentException("session id cannot be blank")
      var sql = "update session_processor set "

      val setFragments = ArrayBuffer[String]()
      val args = ArrayBuffer[String]()
      if (!StringUtils.isBlank(sessionMeta.status)) {
        setFragments += "status = ?"
        args += sessionMeta.status
      }
      if (!StringUtils.isBlank(sessionMeta.tag)) {
        setFragments += "tag = ?"
        args += sessionMeta.tag
      }

      sql += String.join(", ", setFragments: _*)

      sql += "where session_id = ?"
      args += sessionMeta.id

      dbc.update(conn, sql, args: _*)
    }
  }
}

object BaseDao {
  class NotExistError(msg: String) extends Exception(msg)

  val dbc: JdbcTemplate = new JdbcTemplate(RdbConnectionPool.dataSource.getConnection)
}
