package tech.mlsql.plugins.mlsql_watcher.db

case class WExecutor(id: Int, clusterName: String, name: String,
                     hostPort: String,
                     totalShuffleRead: Long,
                     totalShuffleWrite: Long,
                     addTime: Long,
                     removeTime: Long,
                     createdAt: Long
                    )

case class WExecutorJob(id: Int, clusterName: String, groupId: String, executorName: String,
                        diskBytesSpilled: Long,
                        shuffleRemoteBytesRead: Long,
                        shuffleLocalBytesRead: Long,
                        shuffleRecordsRead: Long,
                        shuffleBytesWritten: Long,
                        shuffleRecordsWritten: Long,
                        addTime: Long,
                        removeTime: Long,
                        createdAt: Long
                       )

case class WJob(id: Int, clusterName: String, groupId: String, executorName: String,
                diskBytesSpilled: Long,
                shuffleRemoteBytesRead: Long,
                shuffleLocalBytesRead: Long,
                shuffleRecordsRead: Long,
                shuffleBytesWritten: Long,
                shuffleRecordsWritten: Long,
                addTime: Long,
                createdAt: Long
               )

case class WKv(id: Int, name: String, value: String)
