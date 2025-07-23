package cn.moonlord.tempfilestorage.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 数据基础类
 */
@Slf4j
@Data
@AllArgsConstructor
@NoArgsConstructor
public class BaseData {

    /**
     * 创建时间
     */
    Long creationTime = System.currentTimeMillis();

    /**
     * 最后更新时间
     */
    Long lastUpdateTime = System.currentTimeMillis();

    /**
     * 删除时间
     */
    Long deletionTime = null;

    /**
     * 删除标记
     */
    Boolean deleted = false;

    public void setDeleted(final Boolean deleted) {
        this.deleted = deleted;
        if (deleted != null && deleted) {
            this.deletionTime = System.currentTimeMillis();
        }
        else {
            this.deletionTime = null;
        }
    }

}
