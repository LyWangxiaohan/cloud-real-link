package net.cloud.service;

import net.cloud.vo.DomainVO;

import java.util.List;

public interface DomainService {
    /**
     * 列举全部可用域名
     * @return
     */
    List<DomainVO> listAll();
}
