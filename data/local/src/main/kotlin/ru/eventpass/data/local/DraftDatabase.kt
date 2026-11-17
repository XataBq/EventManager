package ru.eventpass.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="draft_accounts")
data class DraftAccountRow(@PrimaryKey val accountId:String,val generation:Long,val enabled:Boolean)

@Entity(tableName="drafts",primaryKeys=["accountId","localId"],indices=[Index(value=["accountId","remoteId"])])
data class DraftRow(
    val accountId:String,
    val localId:String,
    val incarnation:String,
    val storageVersion:Long,
    val remoteId:String?=null,
    val slug:String?=null,
    val confirmed:String?=null,
    val base:String?=null,
    val pending:String?=null,
    val localRevision:Long=0,
    val syncState:String="LOCAL",
    val command:String?=null,
    val remoteConflict:String?=null,
    val coverReference:String?=null,
    val problem:String?=null,
    val invalidFields:String="[]",
    val retryAt:Long?=null,
    val serverStatus:String?=null,
    val finishedAt:Long?=null,
    val uploadedCover:String?=null,
)

@Dao
abstract class DraftDao {
    @Query("SELECT * FROM draft_accounts WHERE accountId=:account")
    abstract suspend fun account(account:String):DraftAccountRow?
    @Insert(onConflict=OnConflictStrategy.REPLACE)
    abstract suspend fun putAccount(row:DraftAccountRow)
    @Query("SELECT EXISTS(SELECT 1 FROM draft_accounts WHERE accountId=:account AND generation=:generation AND enabled=1)")
    abstract suspend fun active(account:String,generation:Long):Boolean
    @Query("SELECT * FROM drafts WHERE accountId=:account AND localId=:id")
    abstract suspend fun read(account:String,id:String):DraftRow?
    @Query("SELECT * FROM drafts WHERE accountId=:account ORDER BY localId")
    abstract suspend fun list(account:String):List<DraftRow>
    @Query("SELECT * FROM drafts WHERE accountId=:account AND remoteId=:id ORDER BY (pending IS NOT NULL) DESC, localRevision DESC, storageVersion DESC LIMIT 1")
    abstract suspend fun remote(account:String,id:String):DraftRow?
    @Query("SELECT d.* FROM drafts d INNER JOIN draft_accounts a ON a.accountId=d.accountId WHERE d.accountId=:account AND a.generation=:generation AND a.enabled=1 ORDER BY d.localId")
    abstract fun observe(account:String,generation:Long):Flow<List<DraftRow>>
    @Insert(onConflict=OnConflictStrategy.ABORT)
    abstract suspend fun insert(row:DraftRow)
    @Update
    abstract suspend fun update(row:DraftRow):Int
    @Query("DELETE FROM drafts WHERE accountId=:account")
    abstract suspend fun deleteAccount(account:String):Int
    @Query("DELETE FROM drafts WHERE accountId=:account AND localId=:id")
    abstract suspend fun delete(account:String,id:String):Int

    @Query("SELECT DISTINCT coverReference FROM drafts WHERE coverReference IS NOT NULL")
    abstract suspend fun coverReferences():List<String>

    @Transaction
    open suspend fun activate(account:String):Long {
        val next=(this.account(account)?.generation ?: 0)+1
        putAccount(DraftAccountRow(account,next,true));return next
    }
    @Transaction
    open suspend fun readActive(account:String,generation:Long,id:String,byRemote:Boolean=false):DraftRow? {
        if(!active(account,generation)) return null
        return if(byRemote) remote(account,id) else read(account,id)
    }
    /** A late response may update an existing incarnation only, never recreate a deleted row. */
    @Transaction
    open suspend fun replace(account:String,generation:Long,expectedVersion:Long?,expectedIncarnation:String?,next:DraftRow):Boolean {
        require(next.accountId==account)
        if(!active(account,generation)) return false
        val current=read(account,next.localId)
        if(expectedVersion==null) {
            if(current!=null) return false
            require(next.storageVersion==1L)
            insert(next);return true
        }
        if(current?.storageVersion!=expectedVersion || current.incarnation!=expectedIncarnation) return false
        require(next.storageVersion==expectedVersion+1 && next.incarnation==current.incarnation)
        return update(next)==1
    }
    @Transaction
    open suspend fun deleteActive(account:String,generation:Long,id:String,incarnation:String,version:Long):Boolean {
        if(!active(account,generation)) return false
        val current=read(account,id) ?: return false
        return if(current.incarnation==incarnation && current.storageVersion==version) delete(account,id)==1 else false
    }
    @Transaction
    open suspend fun purge(account:String):List<DraftRow> {
        val rows=list(account)
        putAccount(DraftAccountRow(account,(this.account(account)?.generation ?: 0)+1,false))
        deleteAccount(account)
        return rows
    }
}

@Database(entities=[DraftRow::class,DraftAccountRow::class],version=1,exportSchema=true)
abstract class DraftDatabase:RoomDatabase() { abstract fun drafts():DraftDao }
