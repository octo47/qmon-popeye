package popeye.util.hbase

import org.apache.hadoop.hbase.client.{Result, Scan, HTablePool, HTableInterface}
import popeye.Logging
import popeye.util.hbase.HBaseUtils.ChunkedResults

private[popeye] trait HBaseUtils extends Logging {

  def hTablePool: HTablePool

  @inline
  protected def withHTable[U](tableName: String)(body: (HTableInterface) => U): U = {
    log.debug("withHTable - trying to get HTable {}", tableName)
    val hTable = hTablePool.getTable(tableName)
    log.debug("withHTable - got HTable {}", tableName)
    try {
      body(hTable)
    } catch {
      case e: Exception =>
        e.printStackTrace()
        throw e
    } finally {
      hTable.close()
    }
  }

  def getChunkedResults(tableName: String, readChunkSize: Int, scans: Seq[Scan]) = {
    require(scans.nonEmpty, "scans sequence is empty")
    new ChunkedResults(tableName, readChunkSize, scans.head, scans.tail, this)
  }

}

object HBaseUtils {

  def addOneIfNotMaximum(unsignedBytes: Array[Byte]): Array[Byte] = {
    val copy = unsignedBytes.clone()
    for (i <- (copy.length - 1) to 0 by -1) {
      if (copy(i) == 0xff.toByte) {
        copy(i) = 0x00.toByte
      } else {
        copy(i) = (copy(i) + 1).toByte
        return copy
      }
    }
    // not possible to add (all bytes are full)
    return unsignedBytes.clone()
  }

  class ChunkedResults(tableName: String,
                       readChunkSize: Int,
                       scan: Scan,
                       nextScans: Seq[Scan],
                       utils: HBaseUtils,
                       skipFirstRow: Boolean = false) {
    def getRows(): (Array[Result], Option[ChunkedResults]) = utils.withHTable(tableName) {
      table =>
        val scanner = table.getScanner(scan)
        val results =
          try {
            if (skipFirstRow) {
              scanner.next()
            }
            scanner.next(readChunkSize)
          }
          finally {scanner.close()}
        val nextQuery =
          if (results.length < readChunkSize) {
            if (nextScans.nonEmpty) {
              val nextResults =
                new ChunkedResults(
                  tableName,
                  readChunkSize,
                  nextScans.head,
                  nextScans.tail,
                  utils,
                  skipFirstRow = false
                )
              Some(nextResults)
            } else {
              None
            }
          } else {
            val lastRow = results.last.getRow
            val nextScan = new Scan(scan)
            nextScan.setStartRow(lastRow)
            val nextResults =
              new ChunkedResults(
                tableName,
                readChunkSize,
                nextScan,
                nextScans,
                utils,
                skipFirstRow = true
              )
            Some(nextResults)
          }
        (results, nextQuery)
    }
  }

}