# Truy vết yêu cầu ↔ test case

> Sinh tự động bằng `python -I tools/qa/trace.py`. Không sửa tay. Nguồn: `docs/06-testing/catalog/`, SRS v0.96.

## Tóm tắt

| Chỉ số | Giá trị |
|---|---|
| Test case | 181 |
| Đã tự động hóa hoặc một phần | 38 (20%) |
| Tự động hóa đầy đủ | 9 |
| P0 (bảo mật, dữ liệu bệnh nhân, an toàn người bệnh) | 106, tự động hóa ít nhất một phần: 25 |

## Theo module và trạng thái

| Module | Tổng | Tự động | Một phần | Chờ viết | Thủ công |
|---|---|---|---|---|---|
| ai | 15 | 0 | 0 | 15 | 0 |
| clinical | 24 | 0 | 0 | 24 | 0 |
| documents | 7 | 0 | 0 | 7 | 0 |
| nfr | 62 | 3 | 12 | 40 | 7 |
| privacy-admin | 15 | 1 | 1 | 13 | 0 |
| scheduling | 20 | 0 | 9 | 11 | 0 |
| security | 28 | 4 | 6 | 18 | 0 |
| system | 10 | 1 | 1 | 8 | 0 |

## Theo mức kiểm thử

| Mức | Tổng | Tự động | Một phần | Chờ viết | Thủ công |
|---|---|---|---|---|---|
| unit | 4 | 1 | 2 | 1 | 0 |
| contract | 1 | 0 | 1 | 0 | 0 |
| integration | 27 | 1 | 6 | 20 | 0 |
| api | 75 | 1 | 11 | 63 | 0 |
| e2e | 10 | 0 | 3 | 7 | 0 |
| security | 8 | 4 | 0 | 4 | 0 |
| performance | 10 | 0 | 4 | 6 | 0 |
| resilience | 10 | 0 | 0 | 10 | 0 |
| ai-eval | 23 | 0 | 0 | 23 | 0 |
| monitoring | 2 | 0 | 0 | 0 | 2 |
| ci | 5 | 1 | 2 | 2 | 0 |
| review | 6 | 1 | 0 | 0 | 5 |

## P0 chưa có test tự động (ưu tiên viết khi tính năng sẵn sàng)

| Test case | Chặn bởi | Người phụ trách |
|---|---|---|
| TC-AC-01.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-01.2 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-01.3 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-03.6 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | qnt124 |
| TC-AC-04.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-04.2 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-05.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-05.2 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-05.3 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-05.3-download-document | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-06.2 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-09.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-09.2 | Chưa có kho audit WORM và API tra cứu (AUD-02, AUD-06). | D2 |
| TC-AC-10.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-10.1-break-glass-alert | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | qnt124 |
| TC-AC-11.2 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | 24021501-hunglk |
| TC-AC-12.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | 24021501-hunglk |
| TC-AC-14.2 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | 24021501-hunglk |
| TC-AC-AL.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-BG.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-BG.2 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-BG.3 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-BK.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | qnt124 |
| TC-AC-CI.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-CP.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-CS.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-EA.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-FB.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | 24021501-hunglk |
| TC-AC-FS.3 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | qnt124 |
| TC-AC-IS.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-LP.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-PG.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | qnt124 |
| TC-AC-RE.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | 24021501-hunglk |
| TC-AC-RG.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-RG.2 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-RS.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | qnt124 |
| TC-AC-SM.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | 24021501-hunglk |
| TC-AC-SM.2 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | 24021501-hunglk |
| TC-AC-SP.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-TL.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-TL.2 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-UD.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-UD.2 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-UP.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-VD.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-VT.1 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-AC-VT.2 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | Her55chers |
| TC-NFR-A4 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-NFR-A5 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-NFR-SEC10 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-NFR-SEC11 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-NFR-SEC12 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-NFR-SEC13 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-NFR-SEC14 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-NFR-SEC16 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-NFR-SEC17 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-NFR-SEC18 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-NFR-SEC19 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-NFR-SEC20 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-NFR-SEC3 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-NFR-SEC7 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-NFR-SEC8 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-SEC-01 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-SEC-03 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-SEC-05 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-SEC-07 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-SEC-12 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-SEC-13 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-SEC-14 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-SEC-15 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-SEC-16 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-SEC-17 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-SEC-18 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-SEC-19 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-SEC-20 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-SEC-21 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-SEC-22 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-SEC-25 | Chờ tính năng hoặc môi trường tương ứng được hiện thực. | viethung12062005 |
| TC-SEC-26 | Cần audit-verify và Blob WORM (AUD-02, AUD-06, IAC-12). | D2 |
| TC-SEC-27 | Giao diện chưa có đăng xuất xóa dữ liệu và chặn Back (WEB-xx). | SA |

## Ma trận yêu cầu → test case

| Yêu cầu | Test case | Trạng thái |
|---|---|---|
| AC-01.1 | TC-AC-01.1 (api, P0) | planned |
| AC-01.2 | TC-AC-01.2 (api, P0) | planned |
| AC-01.3 | TC-AC-01.3 (api, P0) | planned |
| AC-02.1 | TC-AC-02.1 (api, P1) | planned |
| AC-02.2 | TC-AC-02.2 (api, P1) | planned |
| AC-02.3 | TC-AC-02.3 (api, P0) | partial |
| AC-03.1 | TC-AC-03.1 (api, P1) | partial |
| AC-03.2 | TC-AC-03.2 (api, P0) | partial |
| AC-03.3 | TC-AC-03.3 (api, P0) | partial |
| AC-03.4 | TC-AC-03.4 (api, P0) | partial |
| AC-03.5 | TC-AC-03.5 (api, P0) | partial |
| AC-03.6 | TC-AC-03.6 (api, P0) | planned |
| AC-04.1 | TC-AC-04.1 (api, P0) | planned |
| AC-04.2 | TC-AC-04.2 (api, P0) | planned |
| AC-05.1 | TC-AC-05.1 (api, P0) | planned |
| AC-05.2 | TC-AC-05.2 (api, P0) | planned |
| AC-05.2 | TC-SEC-14 (integration, P0) | planned |
| AC-05.3 | TC-AC-05.3 (api, P0) | planned |
| AC-05.3 | TC-AC-05.3-download-document (api, P0) | planned |
| AC-06.1 | TC-AC-06.1 (api, P1) | planned |
| AC-06.2 | TC-AC-06.2 (api, P0) | planned |
| AC-07.1 | TC-AC-07.1 (api, P1) | planned |
| AC-07.1 | TC-AC-07.1-cancel-reminders (integration, P1) | planned |
| AC-07.2 | TC-AC-07.2 (integration, P1) | partial |
| AC-08.1 | TC-AC-08.1 (api, P0) | partial |
| AC-08.2 | TC-AC-08.2 (api, P0) | automated |
| AC-08.2 | TC-SEC-06 (unit, P0) | automated |
| AC-09.1 | TC-AC-09.1 (api, P0) | planned |
| AC-09.2 | TC-AC-09.2 (e2e, P0) | planned |
| AC-09.2 | TC-SEC-26 (integration, P0) | planned |
| AC-10.1 | TC-AC-10.1 (api, P0) | planned |
| AC-10.1 | TC-SEC-25 (api, P0) | planned |
| AC-10.1 | TC-AC-10.1-break-glass-alert (integration, P0) | planned |
| AC-11.1 | TC-AC-11.1 (ai-eval, P1) | planned |
| AC-11.2 | TC-AC-11.2 (ai-eval, P0) | planned |
| AC-11.2 | TC-SEC-17 (ai-eval, P0) | planned |
| AC-11.3 | TC-AC-11.3 (ai-eval, P1) | planned |
| AC-12.1 | TC-AC-12.1 (ai-eval, P0) | planned |
| AC-12.1 | TC-SEC-19 (integration, P0) | planned |
| AC-12.2 | TC-AC-12.2 (ai-eval, P1) | planned |
| AC-13.1 | TC-AC-13.1 (api, P1) | planned |
| AC-13.2 | TC-AC-13.2 (integration, P1) | planned |
| AC-14.1 | TC-AC-14.1 (ai-eval, P1) | planned |
| AC-14.2 | TC-AC-14.2 (ai-eval, P0) | planned |
| AC-14.3 | TC-AC-14.3 (ai-eval, P1) | planned |
| AC-15.1 | TC-AC-15.1 (integration, P1) | planned |
| AC-15.2 | TC-AC-15.2 (integration, P1) | planned |
| AC-16.1 | TC-AC-16.1 (resilience, P1) | planned |
| AC-16.2 | TC-AC-16.2 (resilience, P1) | planned |
| AC-AD.1 | TC-AC-AD.1 (e2e, P1) | planned |
| AC-AL.1 | TC-AC-AL.1 (api, P0) | planned |
| AC-AS.1 | TC-AC-AS.1 (ai-eval, P1) | planned |
| AC-BG.1 | TC-AC-BG.1 (api, P0) | planned |
| AC-BG.1 | TC-SEC-25 (api, P0) | planned |
| AC-BG.2 | TC-AC-BG.2 (api, P0) | planned |
| AC-BG.3 | TC-AC-BG.3 (api, P0) | planned |
| AC-BG.3 | TC-SEC-25 (api, P0) | planned |
| AC-BK.1 | TC-AC-BK.1 (integration, P0) | planned |
| AC-CI.1 | TC-AC-CI.1 (api, P0) | planned |
| AC-CN.1 | TC-AC-CN.1 (api, P1) | partial |
| AC-CP.1 | TC-AC-CP.1 (e2e, P0) | planned |
| AC-CS.1 | TC-AC-CS.1 (api, P0) | planned |
| AC-DL.1 | TC-AC-DL.1 (api, P1) | planned |
| AC-DR.1 | TC-AC-DR.1 (api, P1) | planned |
| AC-DX.1 | TC-AC-DX.1 (api, P1) | planned |
| AC-EA.1 | TC-AC-EA.1 (api, P0) | planned |
| AC-EX.1 | TC-AC-EX.1 (api, P1) | planned |
| AC-FB.1 | TC-AC-FB.1 (api, P0) | planned |
| AC-FS.1 | TC-AC-FS.1 (api, P1) | partial |
| AC-FS.2 | TC-AC-FS.2 (api, P1) | planned |
| AC-FS.3 | TC-AC-FS.3 (api, P0) | planned |
| AC-GS.1 | TC-AC-GS.1 (api, P1) | planned |
| AC-IS.1 | TC-AC-IS.1 (api, P0) | planned |
| AC-KB.1 | TC-AC-KB.1 (ai-eval, P1) | planned |
| AC-LP.1 | TC-AC-LP.1 (api, P0) | planned |
| AC-MA.1 | TC-AC-MA.1 (api, P0) | partial |
| AC-MG.1 | TC-AC-MG.1 (api, P1) | planned |
| AC-NP.1 | TC-AC-NP.1 (api, P1) | planned |
| AC-NS.1 | TC-AC-NS.1 (integration, P1) | planned |
| AC-PG.1 | TC-AC-PG.1 (integration, P0) | planned |
| AC-RE.1 | TC-AC-RE.1 (api, P0) | planned |
| AC-RG.1 | TC-AC-RG.1 (api, P0) | planned |
| AC-RG.2 | TC-AC-RG.2 (api, P0) | planned |
| AC-RS.1 | TC-AC-RS.1 (api, P0) | planned |
| AC-RS.2 | TC-AC-RS.2 (api, P1) | planned |
| AC-SM.1 | TC-AC-SM.1 (ai-eval, P0) | planned |
| AC-SM.2 | TC-AC-SM.2 (ai-eval, P0) | planned |
| AC-SM.2 | TC-SEC-18 (integration, P0) | planned |
| AC-SN.1 | TC-AC-SN.1 (integration, P1) | planned |
| AC-SP.1 | TC-AC-SP.1 (api, P0) | planned |
| AC-SR.1 | TC-AC-SR.1 (integration, P1) | automated |
| AC-ST.1 | TC-AC-ST.1 (api, P1) | planned |
| AC-TL.1 | TC-AC-TL.1 (api, P0) | planned |
| AC-TL.2 | TC-AC-TL.2 (api, P0) | planned |
| AC-UD.1 | TC-AC-UD.1 (api, P0) | planned |
| AC-UD.2 | TC-AC-UD.2 (integration, P0) | planned |
| AC-UP.1 | TC-AC-UP.1 (api, P0) | planned |
| AC-VD.1 | TC-AC-VD.1 (api, P0) | planned |
| AC-VT.1 | TC-AC-VT.1 (api, P0) | planned |
| AC-VT.2 | TC-AC-VT.2 (api, P0) | planned |
| NFR-A1 | TC-NFR-A1 (monitoring, P1) | manual |
| NFR-A2 | TC-NFR-A2 (resilience, P1) | planned |
| NFR-A3 | TC-NFR-A3 (resilience, P1) | planned |
| NFR-A4 | TC-NFR-A4 (resilience, P0) | planned |
| NFR-A5 | TC-NFR-A5 (resilience, P0) | planned |
| NFR-A6 | TC-NFR-A6 (resilience, P1) | planned |
| NFR-A7 | TC-NFR-A7 (performance, P1) | planned |
| NFR-AI1 | TC-NFR-AI1 (ai-eval, P1) | planned |
| NFR-AI2 | TC-NFR-AI2 (ai-eval, P1) | planned |
| NFR-AI3 | TC-NFR-AI3 (ai-eval, P1) | planned |
| NFR-AI3 | TC-SEC-17 (ai-eval, P0) | planned |
| NFR-AI4 | TC-NFR-AI4 (ai-eval, P1) | planned |
| NFR-AI5 | TC-NFR-AI5 (ai-eval, P1) | planned |
| NFR-AI6 | TC-NFR-AI6 (ai-eval, P1) | planned |
| NFR-AI7 | TC-NFR-AI7 (ai-eval, P1) | planned |
| NFR-C1 | TC-NFR-C1 (review, P1) | manual |
| NFR-C2 | TC-NFR-C2 (monitoring, P1) | manual |
| NFR-C3 | TC-NFR-C3 (review, P1) | manual |
| NFR-D1 | TC-NFR-D1 (ci, P1) | planned |
| NFR-D2 | TC-NFR-D2 (resilience, P1) | planned |
| NFR-D3 | TC-NFR-D3 (resilience, P1) | planned |
| NFR-D4 | TC-NFR-D4 (review, P1) | manual |
| NFR-I1 | TC-NFR-I1 (contract, P1) | partial |
| NFR-I2 | TC-NFR-I2 (e2e, P1) | planned |
| NFR-I3 | TC-NFR-I3 (ci, P1) | planned |
| NFR-O1 | TC-NFR-O1 (integration, P1) | partial |
| NFR-O2 | TC-NFR-O2 (review, P1) | manual |
| NFR-O3 | TC-NFR-O3 (resilience, P1) | planned |
| NFR-O4 | TC-NFR-O4 (integration, P1) | partial |
| NFR-O4 | TC-SEC-11 (integration, P0) | partial |
| NFR-P1 | TC-NFR-P1 (performance, P1) | partial |
| NFR-P2 | TC-NFR-P2 (performance, P1) | partial |
| NFR-P3 | TC-NFR-P3 (performance, P1) | planned |
| NFR-P4 | TC-NFR-P4 (performance, P1) | planned |
| NFR-P5 | TC-NFR-P5 (ai-eval, P1) | planned |
| NFR-P6 | TC-NFR-P6 (ai-eval, P1) | planned |
| NFR-P7 | TC-NFR-P7 (performance, P1) | planned |
| NFR-P8 | TC-NFR-P8 (integration, P1) | planned |
| NFR-S1 | TC-NFR-S1 (performance, P1) | planned |
| NFR-S2 | TC-NFR-S2 (performance, P1) | partial |
| NFR-S3 | TC-NFR-S3 (performance, P1) | partial |
| NFR-S4 | TC-NFR-S4 (performance, P1) | planned |
| NFR-SEC1 | TC-NFR-SEC1 (integration, P0) | partial |
| NFR-SEC1 | TC-SEC-04 (unit, P0) | partial |
| NFR-SEC1 | TC-SEC-05 (api, P0) | planned |
| NFR-SEC1 | TC-SEC-06 (unit, P0) | automated |
| NFR-SEC1 | TC-SEC-28 (e2e, P0) | partial |
| NFR-SEC10 | TC-NFR-SEC10 (api, P0) | planned |
| NFR-SEC10 | TC-SEC-03 (api, P0) | planned |
| NFR-SEC10 | TC-SEC-16 (api, P0) | planned |
| NFR-SEC11 | TC-NFR-SEC11 (api, P0) | planned |
| NFR-SEC12 | TC-NFR-SEC12 (e2e, P0) | planned |
| NFR-SEC13 | TC-NFR-SEC13 (integration, P0) | planned |
| NFR-SEC13 | TC-SEC-01 (api, P0) | planned |
| NFR-SEC13 | TC-SEC-11 (integration, P0) | partial |
| NFR-SEC13 | TC-SEC-12 (api, P0) | planned |
| NFR-SEC14 | TC-NFR-SEC14 (e2e, P0) | planned |
| NFR-SEC14 | TC-SEC-27 (e2e, P0) | planned |
| NFR-SEC14 | TC-SEC-28 (e2e, P0) | partial |
| NFR-SEC15 | TC-NFR-SEC15 (security, P0) | automated |
| NFR-SEC15 | TC-SEC-08 (e2e, P0) | partial |
| NFR-SEC15 | TC-SEC-09 (security, P0) | automated |
| NFR-SEC15 | TC-SEC-10 (security, P0) | automated |
| NFR-SEC16 | TC-NFR-SEC16 (api, P0) | planned |
| NFR-SEC16 | TC-SEC-15 (api, P0) | planned |
| NFR-SEC17 | TC-NFR-SEC17 (integration, P0) | planned |
| NFR-SEC17 | TC-SEC-13 (integration, P0) | planned |
| NFR-SEC17 | TC-SEC-14 (integration, P0) | planned |
| NFR-SEC18 | TC-NFR-SEC18 (api, P0) | planned |
| NFR-SEC19 | TC-NFR-SEC19 (integration, P0) | planned |
| NFR-SEC2 | TC-NFR-SEC2 (unit, P0) | partial |
| NFR-SEC2 | TC-SEC-01 (api, P0) | planned |
| NFR-SEC2 | TC-SEC-02 (api, P0) | partial |
| NFR-SEC2 | TC-SEC-03 (api, P0) | planned |
| NFR-SEC2 | TC-SEC-05 (api, P0) | planned |
| NFR-SEC2 | TC-SEC-07 (api, P0) | planned |
| NFR-SEC2 | TC-SEC-20 (integration, P0) | planned |
| NFR-SEC20 | TC-NFR-SEC20 (unit, P0) | planned |
| NFR-SEC21 | TC-NFR-SEC21 (review, P0) | manual |
| NFR-SEC3 | TC-NFR-SEC3 (security, P0) | planned |
| NFR-SEC3 | TC-SEC-22 (security, P0) | planned |
| NFR-SEC4 | TC-NFR-SEC4 (security, P0) | automated |
| NFR-SEC4 | TC-SEC-23 (ci, P0) | automated |
| NFR-SEC5 | TC-NFR-SEC5 (integration, P0) | partial |
| NFR-SEC5 | TC-SEC-26 (integration, P0) | planned |
| NFR-SEC6 | TC-NFR-SEC6 (ci, P0) | partial |
| NFR-SEC6 | TC-SEC-24 (ci, P0) | partial |
| NFR-SEC7 | TC-NFR-SEC7 (security, P0) | planned |
| NFR-SEC7 | TC-SEC-20 (integration, P0) | planned |
| NFR-SEC7 | TC-SEC-21 (security, P0) | planned |
| NFR-SEC8 | TC-NFR-SEC8 (ai-eval, P0) | planned |
| NFR-SEC8 | TC-SEC-17 (ai-eval, P0) | planned |
| NFR-SEC8 | TC-SEC-18 (integration, P0) | planned |
| NFR-SEC8 | TC-SEC-19 (integration, P0) | planned |
| NFR-SEC9 | TC-NFR-SEC9 (review, P0) | automated |
| NFR-U1 | TC-NFR-U1 (e2e, P1) | partial |
