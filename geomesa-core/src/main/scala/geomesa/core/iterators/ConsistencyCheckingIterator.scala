/*
 * Copyright 2013 Commonwealth Computer Research, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package geomesa.core.iterators

import geomesa.core.index.IndexSchema
import org.apache.accumulo.core.data._
import org.apache.accumulo.core.iterators.{IteratorEnvironment, SortedKeyValueIterator}
import org.apache.hadoop.io.Text
import org.apache.log4j.Logger

class ConsistencyCheckingIterator extends SortedKeyValueIterator[Key, Value] {

  import collection.JavaConverters._

  private var indexSource: SortedKeyValueIterator[Key, Value] = null
  private var dataSource: SortedKeyValueIterator[Key, Value] = null

  private var topKey: Key = null
  private val topValue: Value = new Value(Array[Byte]())
  private var nextKey: Key = null
  private var curId: String = null
  private val log = Logger.getLogger(classOf[ConsistencyCheckingIterator])

  def init(source: SortedKeyValueIterator[Key, Value],
           options: java.util.Map[String, String],
           env: IteratorEnvironment) {
    log.debug("Checking consistency")
    this.indexSource = source.deepCopy(env)
    this.dataSource = source.deepCopy(env)
  }

  def hasTop = nextKey != null || topKey != null

  def getTopKey = topKey

  def getTopValue = topValue

  def findTop() {
    log.trace("Finding top")
    // clear out the reference to the next entry
    nextKey = null

    def isData = indexSource.getTopKey.getRow().toString.startsWith("~") ||
      indexSource.getTopKey.getColumnFamily().toString.startsWith("|data|")

    while (nextKey == null && indexSource.hasTop && !isData) {
      log.trace(s"Checking ${indexSource.getTopKey}")
      nextKey = indexSource.getTopKey
      curId = IndexSchema.decodeIndexValue(indexSource.getTopValue).id

      val dataSeekKey = new Key(indexSource.getTopKey.getRow, new Text(curId))
      val range = new Range(dataSeekKey, null)
      val colFamilies = List[ByteSequence](new ArrayByteSequence(curId.getBytes)).asJavaCollection
      dataSource.seek(range, colFamilies, true)

      if(!dataSource.hasTop || dataSource.getTopKey.getColumnFamily.toString != curId) {
        log.debug(s"Found an inconsistent entry: ${indexSource.getTopKey}")
        nextKey = indexSource.getTopKey
        indexSource.next()
      } else {
        nextKey = null
        indexSource.next()
        while (indexSource != null && indexSource.hasTop && isData)
          indexSource.next()
      }
    }
  }

  def next(): Unit = {
    if(nextKey != null) {
      topKey = nextKey
      findTop()
    } else {
      topKey = null
    }
  }

  def seek(range: Range, columnFamilies: java.util.Collection[ByteSequence], inclusive: Boolean): Unit = {
    log.trace(s"Seeking $range")
    indexSource.seek(range, columnFamilies, inclusive)
    findTop()

    if(nextKey != null) next()
  }

  def deepCopy(env: IteratorEnvironment): SortedKeyValueIterator[Key, Value] = throw new UnsupportedOperationException()
}
