"""Kho tri thức GIẢ cho đánh giá RAG (AI-14, AI-15). Nội dung do nhóm viết để kiểm thử, KHÔNG phải phác đồ thật,
không dùng cho người bệnh. Mỗi mục có một câu dữ kiện và một câu hỏi diễn đạt khác đi; đáp án chuẩn = mục đó.
"""

from dataclasses import dataclass

DISCLAIMER = "> Tài liệu GIẢ phục vụ kiểm thử hệ thống SHP. Không dùng trong điều trị."


@dataclass(frozen=True)
class Fact:
    section: str
    text: str
    question: str


@dataclass(frozen=True)
class KbDoc:
    key: str
    department: str
    title: str
    roles: tuple[str, ...]
    facts: tuple[Fact, ...]


F = Fact
DOCS: tuple[KbDoc, ...] = (
    KbDoc(
        "tha",
        "cardiology",
        "Phác đồ tăng huyết áp (giả)",
        ("doctor", "nurse"),
        (
            F(
                "1. Mục tiêu điều trị",
                "Mục tiêu huyết áp cho đa số người trưởng thành là dưới 140/90 mmHg.",
                "Đích huyết áp cần đạt ở người lớn là bao nhiêu?",
            ),
            F(
                "2. Đo huyết áp",
                "Đo huyết áp sau khi người bệnh ngồi nghỉ ít nhất 5 phút, đo hai lần cách nhau 1 phút.",
                "Trước khi đo huyết áp người bệnh phải nghỉ bao lâu?",
            ),
            F(
                "3. Thay đổi lối sống",
                "Khuyến cáo ăn dưới 5 gam muối mỗi ngày và vận động 150 phút mỗi tuần.",
                "Lượng muối khuyến cáo mỗi ngày cho người tăng huyết áp?",
            ),
            F(
                "4. Chuyển tuyến",
                "Chuyển tuyến khi huyết áp từ 180/110 mmHg trở lên kèm dấu hiệu tổn thương cơ quan đích.",
                "Ngưỡng huyết áp nào cần chuyển tuyến?",
            ),
            F(
                "5. Tái khám",
                "Người bệnh ổn định tái khám mỗi 3 tháng để đánh giá đáp ứng điều trị.",
                "Bệnh nhân tăng huyết áp ổn định tái khám định kỳ bao lâu một lần?",
            ),
            F(
                "6. Theo dõi tại nhà",
                "Hướng dẫn người bệnh ghi nhật ký huyết áp sáng và tối trong 7 ngày trước tái khám.",
                "Nhật ký huyết áp tại nhà cần ghi trong mấy ngày?",
            ),
            F(
                "7. Cấp cứu",
                "Cơn tăng huyết áp cấp cứu cần theo dõi tại khoa hồi sức và hạ áp từ từ trong giờ đầu.",
                "Xử trí cơn tăng huyết áp cấp cứu ở đâu?",
            ),
            F(
                "8. Phụ nữ mang thai",
                "Phụ nữ mang thai tăng huyết áp phải được khám phối hợp với khoa sản.",
                "Thai phụ bị tăng huyết áp cần phối hợp với khoa nào?",
            ),
            F(
                "9. Giáo dục",
                "Điều dưỡng hướng dẫn người bệnh nhận biết đau đầu dữ dội, nhìn mờ là dấu hiệu cần đến viện ngay.",
                "Dấu hiệu nào khiến người tăng huyết áp phải đến viện ngay?",
            ),
        ),
    ),
    KbDoc(
        "dtd",
        "internal-medicine",
        "Phác đồ đái tháo đường típ 2 (giả)",
        ("doctor", "nurse"),
        (
            F(
                "1. Chẩn đoán",
                "Chẩn đoán khi glucose huyết tương lúc đói từ 7,0 mmol/L trở lên ở hai lần xét nghiệm.",
                "Glucose lúc đói bao nhiêu thì chẩn đoán đái tháo đường?",
            ),
            F("2. HbA1c", "Mục tiêu HbA1c cho đa số người bệnh là dưới 7 phần trăm.", "HbA1c mục tiêu là bao nhiêu?"),
            F(
                "3. Theo dõi",
                "Xét nghiệm HbA1c mỗi 3 tháng khi chưa đạt mục tiêu và mỗi 6 tháng khi đã ổn định.",
                "Bao lâu thì làm lại HbA1c khi bệnh đã ổn định?",
            ),
            F(
                "4. Biến chứng mắt",
                "Khám đáy mắt mỗi năm một lần để phát hiện bệnh võng mạc.",
                "Người đái tháo đường cần khám đáy mắt định kỳ thế nào?",
            ),
            F(
                "5. Chăm sóc bàn chân",
                "Kiểm tra bàn chân ở mỗi lần khám, chú ý vết loét và mất cảm giác.",
                "Khi nào cần kiểm tra bàn chân người bệnh đái tháo đường?",
            ),
            F(
                "6. Hạ đường huyết",
                "Hạ đường huyết khi glucose dưới 3,9 mmol/L; cho uống 15 gam đường nhanh và đo lại sau 15 phút.",
                "Xử trí ban đầu hạ đường huyết như thế nào?",
            ),
            F(
                "7. Dinh dưỡng",
                "Chia nhỏ bữa ăn, hạn chế đồ uống có đường và ưu tiên rau xanh.",
                "Lời khuyên ăn uống cho người đái tháo đường?",
            ),
            F(
                "8. Chuyển chuyên khoa",
                "Chuyển khoa nội tiết khi HbA1c trên 9 phần trăm sau 3 tháng điều trị.",
                "Khi nào chuyển người bệnh đái tháo đường lên khoa nội tiết?",
            ),
        ),
    ),
    KbDoc(
        "hen",
        "pediatrics",
        "Hướng dẫn xử trí hen trẻ em (giả)",
        ("doctor", "nurse"),
        (
            F(
                "1. Đánh giá mức độ",
                "Đánh giá cơn hen dựa trên nhịp thở, độ bão hòa oxy và khả năng nói thành câu.",
                "Đánh giá mức độ cơn hen ở trẻ dựa vào gì?",
            ),
            F("2. Oxy", "Cho thở oxy khi độ bão hòa oxy dưới 94 phần trăm.", "SpO2 bao nhiêu thì cho trẻ hen thở oxy?"),
            F(
                "3. Khí dung",
                "Khí dung thuốc giãn phế quản mỗi 20 phút trong giờ đầu, đánh giá lại sau mỗi lần.",
                "Trong giờ đầu, khí dung giãn phế quản cách nhau bao lâu?",
            ),
            F(
                "4. Nhập viện",
                "Nhập viện khi trẻ không đáp ứng sau 3 lần khí dung hoặc tím tái.",
                "Trẻ hen cần nhập viện khi nào?",
            ),
            F(
                "5. Hướng dẫn phụ huynh",
                "Hướng dẫn phụ huynh dùng buồng đệm khi xịt thuốc cho trẻ dưới 5 tuổi.",
                "Trẻ dưới 5 tuổi xịt thuốc hen cần dụng cụ gì?",
            ),
            F(
                "6. Tái khám",
                "Tái khám sau 1 tuần kể từ khi ra viện để chỉnh thuốc dự phòng.",
                "Trẻ hen ra viện thì bao lâu tái khám?",
            ),
            F(
                "7. Yếu tố khởi phát",
                "Tránh khói thuốc lá, lông thú và bụi nhà là các yếu tố khởi phát thường gặp.",
                "Những yếu tố nào hay làm khởi phát cơn hen ở trẻ?",
            ),
            F(
                "8. Sốt kèm theo",
                "Trẻ em bị sốt kèm khò khè cần khám loại trừ viêm phổi.",
                "Trẻ vừa sốt vừa khò khè cần loại trừ bệnh gì?",
            ),
        ),
    ),
    KbDoc(
        "thai",
        "obstetrics-gynecology",
        "Quy trình khám thai (giả)",
        ("doctor", "nurse"),
        (
            F(
                "1. Lịch khám",
                "Khám thai ít nhất 4 lần trong thai kỳ, lần đầu trước tuần 12.",
                "Thai phụ cần khám thai tối thiểu mấy lần?",
            ),
            F(
                "2. Siêu âm hình thái",
                "Siêu âm hình thái học thai nhi vào tuần 20 đến 22.",
                "Siêu âm hình thái thai nhi làm ở tuần nào?",
            ),
            F(
                "3. Nghiệm pháp dung nạp glucose",
                "Làm nghiệm pháp dung nạp glucose 75 gam ở tuần 24 đến 28.",
                "Tầm soát đái tháo đường thai kỳ vào thời điểm nào?",
            ),
            F(
                "4. Sắt và axit folic",
                "Bổ sung sắt và axit folic suốt thai kỳ theo hướng dẫn của bác sĩ.",
                "Thai phụ cần bổ sung vi chất gì trong suốt thai kỳ?",
            ),
            F(
                "5. Dấu hiệu nguy hiểm",
                "Ra máu âm đạo, đau bụng dữ dội hoặc thai máy giảm là dấu hiệu cần đến viện ngay.",
                "Dấu hiệu bất thường nào khi mang thai cần nhập viện ngay?",
            ),
            F(
                "6. Tiêm phòng",
                "Tiêm phòng uốn ván theo lịch của chương trình tiêm chủng.",
                "Thai phụ cần tiêm phòng bệnh gì?",
            ),
            F(
                "7. Đếm cử động thai",
                "Từ tuần 28, hướng dẫn thai phụ đếm cử động thai mỗi ngày.",
                "Từ tuần thai thứ mấy bắt đầu đếm cử động thai?",
            ),
            F(
                "8. Sinh tại viện",
                "Khuyến khích sinh tại cơ sở y tế có phòng mổ cấp cứu.",
                "Nên sinh ở cơ sở y tế như thế nào?",
            ),
        ),
    ),
    KbDoc(
        "da",
        "dermatology",
        "Hướng dẫn chăm sóc viêm da cơ địa (giả)",
        ("doctor", "nurse"),
        (
            F(
                "1. Dưỡng ẩm",
                "Bôi kem dưỡng ẩm ít nhất 2 lần mỗi ngày, kể cả khi da không có tổn thương.",
                "Người viêm da cơ địa nên bôi dưỡng ẩm mấy lần một ngày?",
            ),
            F(
                "2. Tắm",
                "Tắm nước ấm dưới 10 phút, tránh xà phòng có tính kiềm.",
                "Tắm thế nào cho người viêm da cơ địa?",
            ),
            F(
                "3. Quần áo",
                "Mặc quần áo cotton mềm, tránh len và vải sợi tổng hợp.",
                "Chất liệu quần áo phù hợp cho người viêm da cơ địa?",
            ),
            F(
                "4. Nhiễm trùng",
                "Tổn thương rỉ dịch vàng, đóng vảy là dấu hiệu bội nhiễm cần khám lại.",
                "Dấu hiệu bội nhiễm ở da viêm cơ địa là gì?",
            ),
            F(
                "5. Tái khám",
                "Tái khám sau 2 tuần khi bắt đầu điều trị đợt cấp.",
                "Điều trị đợt cấp viêm da cơ địa thì bao lâu tái khám?",
            ),
            F(
                "6. Giáo dục",
                "Hướng dẫn cắt ngắn móng tay để hạn chế tổn thương do gãi.",
                "Làm gì để hạn chế tổn thương do gãi?",
            ),
            F(
                "7. Chuyển tuyến",
                "Chuyển tuyến khi tổn thương lan trên 30 phần trăm diện tích da.",
                "Viêm da lan rộng bao nhiêu thì chuyển tuyến?",
            ),
            F(
                "8. Môi trường",
                "Giữ độ ẩm phòng ngủ vừa phải và tránh nhiệt độ quá nóng.",
                "Môi trường phòng ngủ phù hợp cho người viêm da?",
            ),
        ),
    ),
    KbDoc(
        "tmh",
        "ent",
        "Hướng dẫn viêm tai giữa cấp (giả)",
        ("doctor",),
        (
            F(
                "1. Chẩn đoán",
                "Chẩn đoán dựa trên màng nhĩ phồng, đỏ và đau tai khởi phát cấp.",
                "Dựa vào đâu để chẩn đoán viêm tai giữa cấp?",
            ),
            F(
                "2. Theo dõi",
                "Có thể theo dõi 48 giờ ở trẻ trên 2 tuổi triệu chứng nhẹ trước khi quyết định điều trị.",
                "Trẻ lớn triệu chứng nhẹ có thể theo dõi bao lâu?",
            ),
            F(
                "3. Giảm đau",
                "Giảm đau và hạ sốt là điều trị nền tảng trong mọi trường hợp.",
                "Điều trị nền tảng của viêm tai giữa cấp là gì?",
            ),
            F(
                "4. Biến chứng",
                "Sưng đau sau tai hoặc liệt mặt là dấu hiệu biến chứng cần nhập viện.",
                "Biến chứng nào của viêm tai giữa cần nhập viện?",
            ),
            F(
                "5. Tái khám",
                "Tái khám sau 3 ngày nếu triệu chứng không cải thiện.",
                "Khi nào bệnh nhân viêm tai giữa cần tái khám?",
            ),
            F(
                "6. Phòng ngừa",
                "Không cho trẻ bú bình khi nằm ngửa để giảm nguy cơ viêm tai.",
                "Tư thế bú nào làm tăng nguy cơ viêm tai ở trẻ?",
            ),
            F(
                "7. Thính lực",
                "Đo thính lực nếu tràn dịch tai giữa kéo dài trên 3 tháng.",
                "Khi nào cần đo thính lực cho trẻ viêm tai?",
            ),
            F(
                "8. Nhỏ tai",
                "Không tự ý nhỏ thuốc vào tai khi chưa khám màng nhĩ.",
                "Có nên tự nhỏ thuốc tai khi chưa khám không?",
            ),
        ),
    ),
    KbDoc(
        "mat",
        "ophthalmology",
        "Hướng dẫn chăm sóc sau mổ đục thủy tinh thể (giả)",
        ("doctor", "nurse"),
        (
            F(
                "1. Băng mắt",
                "Giữ băng che mắt trong đêm đầu tiên sau mổ.",
                "Sau mổ đục thủy tinh thể phải che mắt bao lâu?",
            ),
            F(
                "2. Nhỏ mắt",
                "Nhỏ thuốc theo đơn và rửa tay trước mỗi lần nhỏ mắt.",
                "Cần làm gì trước khi nhỏ thuốc mắt sau mổ?",
            ),
            F(
                "3. Sinh hoạt",
                "Tránh cúi đầu thấp và mang vật nặng trong 2 tuần đầu.",
                "Kiêng hoạt động gì sau mổ thủy tinh thể?",
            ),
            F(
                "4. Tắm gội",
                "Không để nước vào mắt khi tắm gội trong 1 tuần.",
                "Bao lâu thì được để nước vào mắt khi gội đầu?",
            ),
            F(
                "5. Dấu hiệu bất thường",
                "Đau nhức tăng, nhìn mờ đột ngột hoặc đỏ mắt nhiều cần khám ngay.",
                "Dấu hiệu nào sau mổ mắt cần đi khám ngay?",
            ),
            F(
                "6. Tái khám",
                "Tái khám ngày đầu, sau 1 tuần và sau 1 tháng.",
                "Lịch tái khám sau mổ đục thủy tinh thể?",
            ),
            F("7. Kính", "Đo kính sau khi mắt ổn định khoảng 4 tuần.", "Khi nào đo kính lại sau mổ?"),
            F(
                "8. Bảo vệ",
                "Đeo kính bảo vệ khi ra ngoài trời nắng hoặc có bụi.",
                "Ra ngoài sau mổ mắt cần bảo vệ thế nào?",
            ),
        ),
    ),
    KbDoc(
        "ctch",
        "orthopedics",
        "Hướng dẫn chăm sóc bó bột (giả)",
        ("doctor", "nurse"),
        (
            F(
                "1. Kê cao chi",
                "Kê cao chi bó bột cao hơn tim trong 48 giờ đầu để giảm phù.",
                "Sau bó bột cần kê cao chi trong bao lâu?",
            ),
            F(
                "2. Hội chứng chèn ép",
                "Đau tăng dần, tê bì, đầu ngón lạnh tím là dấu hiệu chèn ép cần báo ngay.",
                "Dấu hiệu chèn ép khi bó bột là gì?",
            ),
            F("3. Giữ khô", "Giữ bột khô, bọc túi nilon khi tắm.", "Khi tắm cần làm gì với bột?"),
            F("4. Ngứa", "Không đưa vật nhọn vào trong bột để gãi.", "Ngứa trong bột có được dùng vật nhọn gãi không?"),
            F(
                "5. Vận động",
                "Vận động các ngón không bị bó nhiều lần trong ngày.",
                "Người bó bột nên vận động thế nào?",
            ),
            F(
                "6. Tái khám",
                "Chụp X-quang kiểm tra sau 1 tuần bó bột.",
                "Sau bó bột bao lâu thì chụp X-quang kiểm tra?",
            ),
            F(
                "7. Tháo bột",
                "Thời gian tháo bột do bác sĩ quyết định sau phim kiểm tra.",
                "Ai quyết định thời điểm tháo bột?",
            ),
            F(
                "8. Phục hồi",
                "Tập phục hồi chức năng sau tháo bột theo hướng dẫn của kỹ thuật viên.",
                "Sau tháo bột cần làm gì để phục hồi?",
            ),
        ),
    ),
    KbDoc(
        "tk",
        "neurology",
        "Hướng dẫn nhận biết đột quỵ (giả)",
        ("doctor", "nurse"),
        (
            F(
                "1. Nhận biết",
                "Nhận biết đột quỵ theo FAST: méo mặt, yếu tay, nói khó, gọi cấp cứu ngay.",
                "Cách nhận biết nhanh đột quỵ là gì?",
            ),
            F(
                "2. Thời gian vàng",
                "Ghi lại thời điểm khởi phát triệu chứng vì quyết định điều trị phụ thuộc thời gian.",
                "Vì sao cần ghi thời điểm khởi phát đột quỵ?",
            ),
            F(
                "3. Đường huyết",
                "Đo đường huyết mao mạch ngay khi tiếp nhận để loại trừ hạ đường huyết.",
                "Xét nghiệm nhanh nào làm ngay khi tiếp nhận nghi đột quỵ?",
            ),
            F(
                "4. Chẩn đoán hình ảnh",
                "Chụp CT sọ não không cản quang càng sớm càng tốt.",
                "Chẩn đoán hình ảnh đầu tiên khi nghi đột quỵ?",
            ),
            F(
                "5. Nuốt",
                "Đánh giá khả năng nuốt trước khi cho ăn uống bằng đường miệng.",
                "Trước khi cho người đột quỵ ăn cần đánh giá gì?",
            ),
            F("6. Tư thế", "Nằm đầu cao 30 độ nếu không có chống chỉ định.", "Tư thế nằm cho người bệnh đột quỵ?"),
            F(
                "7. Phục hồi",
                "Bắt đầu phục hồi chức năng sớm khi tình trạng ổn định.",
                "Khi nào bắt đầu phục hồi chức năng sau đột quỵ?",
            ),
            F(
                "8. Phòng tái phát",
                "Kiểm soát huyết áp và bỏ thuốc lá để phòng đột quỵ tái phát.",
                "Biện pháp phòng đột quỵ tái phát?",
            ),
        ),
    ),
    KbDoc(
        "ksnk",
        "hospital-wide",
        "Quy định kiểm soát nhiễm khuẩn (giả)",
        ("doctor", "nurse"),
        (
            F(
                "1. Vệ sinh tay",
                "Vệ sinh tay theo 5 thời điểm, chà tay với dung dịch cồn ít nhất 20 giây.",
                "Chà tay bằng dung dịch cồn tối thiểu bao lâu?",
            ),
            F(
                "2. Găng tay",
                "Thay găng giữa hai người bệnh và vệ sinh tay sau khi tháo găng.",
                "Sau khi tháo găng tay cần làm gì?",
            ),
            F(
                "3. Vật sắc nhọn",
                "Bỏ kim tiêm vào hộp an toàn ngay sau khi dùng, không đậy nắp kim.",
                "Xử lý kim tiêm sau sử dụng thế nào?",
            ),
            F(
                "4. Phơi nhiễm",
                "Báo cáo phơi nhiễm do vật sắc nhọn trong vòng 2 giờ cho khoa kiểm soát nhiễm khuẩn.",
                "Bị kim đâm phải báo cáo trong bao lâu?",
            ),
            F(
                "5. Cách ly",
                "Người bệnh nghi lao phổi được đeo khẩu trang và chuyển phòng cách ly.",
                "Người bệnh nghi lao phổi cần được xử trí thế nào khi tiếp nhận?",
            ),
            F("6. Chất thải", "Chất thải lây nhiễm đựng trong túi màu vàng.", "Chất thải lây nhiễm bỏ vào túi màu gì?"),
            F(
                "7. Khử khuẩn bề mặt",
                "Lau khử khuẩn bề mặt giường bệnh mỗi ngày và khi người bệnh ra viện.",
                "Bao lâu thì lau khử khuẩn giường bệnh?",
            ),
            F(
                "8. Tiêm chủng nhân viên",
                "Nhân viên y tế được tiêm phòng viêm gan B theo lịch của bệnh viện.",
                "Nhân viên y tế được tiêm phòng bệnh gì?",
            ),
            F(
                "9. Khẩu trang",
                "Đeo khẩu trang y tế khi chăm sóc người bệnh có triệu chứng hô hấp.",
                "Khi nào nhân viên cần đeo khẩu trang y tế?",
            ),
        ),
    ),
)

# Câu hỏi KHÔNG có đáp án trong kho (từ chối đúng: answered=false)
UNANSWERABLE: tuple[str, ...] = (
    "Giá một lần chụp MRI ở bệnh viện là bao nhiêu?",
    "Quy trình xin nghỉ phép năm của nhân viên?",
    "Liều hóa trị cho ung thư phổi giai đoạn 4?",
    "Bãi đỗ xe của bệnh viện mở cửa mấy giờ?",
    "Phác đồ điều trị sốt xuất huyết Dengue?",
    "Tiêu chuẩn ghép thận cho người hiến sống?",
    "Lịch trực Tết của khoa cấp cứu năm nay?",
    "Cách cài máy in trong phòng khám?",
    "Phác đồ điều trị viêm gan C bằng thuốc kháng virus?",
    "Quy trình nội soi đại tràng gây mê?",
    "Mức lương trực đêm của điều dưỡng?",
    "Chỉ định phẫu thuật thay khớp háng?",
    "Điều trị bỏng độ 3 diện rộng?",
    "Liều vắc xin dại sau khi bị chó cắn?",
    "Quy trình chạy thận nhân tạo cấp cứu?",
    "Thực đơn căng tin bệnh viện tuần này?",
    "Cách đặt nội khí quản ở trẻ sơ sinh?",
    "Cách tính điểm thi đua cuối năm của khoa?",
)


def markdown(doc: KbDoc) -> bytes:
    lines = [f"# {doc.title}", "", DISCLAIMER, ""]
    for f in doc.facts:
        lines += [f"## {f.section}", f.text, ""]
    return "\n".join(lines).encode("utf-8")
