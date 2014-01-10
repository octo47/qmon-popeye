package popeye.util.hbase

import org.apache.hadoop.hbase.{HConstants, HBaseConfiguration}
import org.apache.hadoop.hbase.client.{Scan, HTablePool}

object HBaseQueryTest {

  def main(args: Array[String]) {
    val hbaseConfiguration = HBaseConfiguration.create
    hbaseConfiguration.set("pool.max", "25")
    hbaseConfiguration.set("zk.quorum", "localhost:2182")
    hbaseConfiguration.setInt(HConstants.ZOOKEEPER_CLIENT_PORT, 2182)
    val hTablePool = new HTablePool(hbaseConfiguration, 1)
    val tsdbTable = hTablePool.getTable("tsdb")
    val scan = new Scan()
    val scanner = try {tsdbTable.getScanner(scan)} finally tsdbTable.close()
    val results = try {scanner.next(10)} finally scanner.close()
    for (result <- results) {
      println(result.getRow.toList)
    }
  }
}